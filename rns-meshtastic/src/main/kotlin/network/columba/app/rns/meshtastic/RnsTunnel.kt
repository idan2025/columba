package network.columba.app.rns.meshtastic

import kotlin.math.abs

/**
 * Kotlin port of the RNS-over-Meshtastic tunnel protocol
 * (landandair/RNS_Over_Meshtastic `Meshtastic_Interface.py`), wire-compatible
 * with it so Columba talks to Python RNS nodes running that interface (and to
 * RetAlert, which carries the same port).
 *
 * An RNS packet (up to [HW_MTU] bytes) is split into fragments that each fit
 * one Meshtastic packet: `[index u8][pos i8] + chunk`, positions counting
 * from 1 and the last one negated. A receiver that sees a gap in a sender's
 * sequence broadcasts `"REQ" + [index][pos]`, and whoever holds that fragment
 * sends it again.
 *
 * Pure state machine, no I/O: [send] queues, [next] hands out the next
 * Meshtastic payload, [receive] consumes one. Not thread-safe; callers serialise.
 */
class RnsTunnel(private val maxPayload: Int = MAX_PAYLOAD) {

    /** One Meshtastic payload to transmit, to [dest] (a node number or BROADCAST). */
    data class Outgoing(val payload: ByteArray, val dest: Long)

    private sealed interface Item {
        data class Fragment(val index: Int, val pos: Int) : Item
        data class Raw(val payload: ByteArray) : Item
    }

    private val queue = ArrayDeque<Item>()
    private val sent = HashMap<Int, FragmentSet>()
    private var packetIndex = 0

    private class Peer {
        val expected = ArrayList<Pair<Int, Int>>()
        val requested = ArrayList<Pair<Int, Int>>()
        val assembly = LinkedHashMap<Int, FragmentSet>()
    }
    private val peers = HashMap<Long, Peer>()

    /** RNS link destination hash -> Meshtastic node that last sent us traffic for it. */
    private val routes = object : LinkedHashMap<ByteKey, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ByteKey, Long>?) = size > MAX_ROUTES
    }

    val queued: Int get() = queue.size

    /** Queue an outgoing RNS packet. Returns false when the queue is full (packet dropped). */
    fun send(data: ByteArray): Boolean {
        if (data.isEmpty() || queue.size >= MAX_QUEUE) return false
        val dest = if (data.size >= 18) routes[ByteKey(data.copyOfRange(2, 18))] ?: MeshProto.BROADCAST else MeshProto.BROADCAST
        val set = FragmentSet.split(data, packetIndex, maxPayload, dest)
        sent[packetIndex] = set
        set.positions().forEach { queue.addLast(Item.Fragment(packetIndex, it)) }
        packetIndex = nextIndex(packetIndex)
        return true
    }

    /** Next payload to put on air, or null when idle. */
    fun next(): Outgoing? {
        while (queue.isNotEmpty()) {
            resolve(queue.removeFirst())?.let { return it }
        }
        return null
    }

    /** The payload for a queued item, or null when its fragment is gone (index reused). */
    private fun resolve(item: Item): Outgoing? =
        when (item) {
            is Item.Raw -> Outgoing(item.payload, MeshProto.BROADCAST)
            is Item.Fragment -> sent[item.index]?.let { set -> set[item.pos]?.let { Outgoing(it, set.dest) } }
        }

    /**
     * Consume one tunnel payload from node [from]. Returns a reassembled RNS
     * packet when this fragment completed one, else null.
     */
    fun receive(from: Long, payload: ByteArray): ByteArray? {
        if (isRequest(payload)) {
            val (idx, pos) = header(payload, 3)
            queue.addFirst(Item.Fragment(idx, pos))
            return null
        }
        if (payload.size <= HEADER) return null
        val peer = peers.getOrPut(from) { Peer() }
        val (idx, pos) = header(payload, 0)
        val key = idx to abs(pos)
        var expectFollowup = true
        when {
            key in peer.expected -> peer.expected.removeAll { it == key }
            key in peer.requested -> { peer.requested.remove(key); expectFollowup = false }
            peer.expected.isNotEmpty() -> {
                // Not what we expected next: ask for the oldest fragment we're missing.
                val missing = peer.expected.removeAt(0)
                peer.requested.add(missing.first to abs(missing.second))
                if (peer.requested.size > MAX_REQUESTED) peer.requested.removeAt(0)
                queue.addFirst(Item.Raw(REQ + byteArrayOf(missing.first.toByte(), missing.second.toByte())))
            }
        }

        val set = peer.assembly.getOrPut(idx) { FragmentSet(idx) }
        val data = set.add(pos, payload)
        if (data != null) {
            peer.assembly.remove(idx)
            learnRoute(data, from)
        }
        while (peer.assembly.size > MAX_PARTIAL) peer.assembly.remove(peer.assembly.keys.first())

        if (expectFollowup) {
            peer.expected.add(0, if (pos < 0) nextIndex(idx) to 1 else idx to pos + 1)
            while (peer.expected.size > MAX_EXPECTED) peer.expected.removeAt(peer.expected.size - 1)
        }
        return data
    }

    private fun isRequest(payload: ByteArray): Boolean =
        payload.size >= REQ.size + HEADER && payload.copyOfRange(0, REQ.size).contentEquals(REQ)

    /** Link traffic (header byte `00..11..`, i.e. HEADER_1 to a LINK destination)
     *  tells us which node a link lives behind, so replies can go unicast. */
    private fun learnRoute(data: ByteArray, from: Long) {
        if (data.size < 18) return
        val flags = data[0].toInt() and 0xFF
        if (flags and 0b1100_0000 == 0 && flags and 0b0000_1100 == 0b0000_1100) {
            routes[ByteKey(data.copyOfRange(2, 18))] = from
        }
    }

    /** Fragments of one RNS packet, keyed by |pos|; each stored with its header. */
    class FragmentSet(val index: Int, val dest: Long = MeshProto.BROADCAST) {
        private val parts = sortedMapOf<Int, ByteArray>()
        private var last = -1

        fun positions(): List<Int> = parts.keys.toList()
        operator fun get(pos: Int): ByteArray? = parts[abs(pos)]

        /** Add a received fragment; returns the packet once every part is in.
         *  (Unlike the Python original this also completes when the last
         *  fragment arrived before a resent middle one.) */
        fun add(pos: Int, fragment: ByteArray): ByteArray? {
            if (pos == 0) return null
            parts[abs(pos)] = fragment
            if (pos < 0) last = abs(pos)
            if (last < 0 || parts.size != last || parts.lastKey() != last) return null
            val out = java.io.ByteArrayOutputStream()
            parts.values.forEach { out.write(it, HEADER, it.size - HEADER) }
            return out.toByteArray()
        }

        companion object {
            /** Same chunking as the Python PacketHandler.split_data: even-sized chunks. */
            fun split(data: ByteArray, index: Int, maxPayload: Int, dest: Long): FragmentSet {
                val set = FragmentSet(index, dest)
                val count = data.size / maxPayload + 1
                val size = data.size / count + 1
                val chunks = (data.indices step size).map { data.copyOfRange(it, minOf(it + size, data.size)) }
                chunks.forEachIndexed { i, chunk ->
                    val pos = if (i == chunks.lastIndex) -(i + 1) else i + 1
                    set.parts[i + 1] = byteArrayOf(index.toByte(), pos.toByte()) + chunk
                }
                set.last = chunks.size
                return set
            }
        }
    }

    private class ByteKey(val b: ByteArray) {
        override fun equals(other: Any?) = other is ByteKey && b.contentEquals(other.b)
        override fun hashCode() = b.contentHashCode()
    }

    companion object {
        /** Python HW_MTU: largest RNS packet the tunnel carries (split in up to 3 fragments). */
        const val HW_MTU = 564
        const val MAX_PAYLOAD = 200
        const val HEADER = 2
        private val REQ = "REQ".encodeToByteArray()
        private const val MAX_QUEUE = 256
        private const val MAX_ROUTES = 20
        private const val MAX_REQUESTED = 10
        private const val MAX_EXPECTED = 32
        private const val MAX_PARTIAL = 16

        fun nextIndex(i: Int) = (i + 1) % 256

        /** `struct.unpack('Bb')`: unsigned index, signed position. */
        fun header(b: ByteArray, at: Int): Pair<Int, Int> = (b[at].toInt() and 0xFF) to b[at + 1].toInt()

        /** Seconds between transmissions per Meshtastic modem preset (Python speed_to_delay). */
        fun sendDelayMs(modemPreset: Int?): Long = when (modemPreset) {
            8 -> 400L      // SHORT_TURBO
            6 -> 1_000L    // SHORT_FAST
            5 -> 3_000L    // SHORT_SLOW
            4 -> 4_000L    // MEDIUM_FAST
            3 -> 6_000L    // MEDIUM_SLOW
            0 -> 8_000L    // LONG_FAST
            7 -> 12_000L   // LONG_MODERATE
            1 -> 15_000L   // LONG_SLOW
            else -> 7_000L
        }
    }
}
