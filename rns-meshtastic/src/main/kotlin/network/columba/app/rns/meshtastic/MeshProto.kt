package network.columba.app.rns.meshtastic

import java.io.ByteArrayOutputStream

/**
 * Just enough of the Meshtastic device API (meshtastic/protobufs mesh.proto,
 * config.proto, channel.proto) to tunnel Reticulum: ToRadio packet /
 * want_config_id / heartbeat out, and the FromRadio variants we act on in.
 * Hand-rolled protobuf so the app needs no codegen or protobuf runtime.
 */
object MeshProto {
    const val BROADCAST: Long = 0xFFFFFFFFL
    const val PORT_RETICULUM_TUNNEL = 76
    const val PORT_PRIVATE_APP = 256
    /** Data.payload limit (mesh.proto DATA_PAYLOAD_LEN). */
    const val DATA_PAYLOAD_LEN = 233

    /** Channel.Role */
    const val ROLE_DISABLED = 0

    // -- ToRadio ---------------------------------------------------------------

    /** ToRadio{packet = MeshPacket{to, channel, decoded = Data{portnum, payload}, id, hop_limit}}. */
    fun toRadioPacket(to: Long, channel: Int, portnum: Int, payload: ByteArray, id: Int, hopLimit: Int): ByteArray {
        val data = Writer().apply {
            varint(1, portnum.toLong())
            bytes(2, payload)
        }.toByteArray()
        val packet = Writer().apply {
            fixed32(2, to)
            if (channel != 0) varint(3, channel.toLong())
            bytes(4, data)
            fixed32(6, id.toLong() and 0xFFFFFFFFL)
            varint(9, hopLimit.toLong())
        }.toByteArray()
        return Writer().apply { bytes(1, packet) }.toByteArray()
    }

    /** ToRadio{want_config_id}: asks the node to (re)send its config, ending in config_complete_id. */
    fun toRadioWantConfig(nonce: Int): ByteArray =
        Writer().apply { varint(3, nonce.toLong() and 0xFFFFFFFFL) }.toByteArray()

    /** ToRadio{heartbeat{}}: keeps serial/TCP API sessions from timing out. */
    fun toRadioHeartbeat(): ByteArray = Writer().apply { bytes(7, ByteArray(0)) }.toByteArray()

    // -- FromRadio -------------------------------------------------------------

    sealed interface FromRadio {
        /** A decoded MeshPacket (encrypted ones we can't read are dropped). */
        data class Packet(val from: Long, val to: Long, val channel: Int, val portnum: Int, val payload: ByteArray) : FromRadio
        data class MyInfo(val nodeNum: Long) : FromRadio
        data class LoraConfig(val usePreset: Boolean, val modemPreset: Int) : FromRadio
        data class Channel(val index: Int, val name: String, val role: Int) : FromRadio
        data class QueueStatus(val free: Int, val maxlen: Int) : FromRadio
        data class ConfigComplete(val nonce: Long) : FromRadio
        data object Rebooted : FromRadio
        data object Other : FromRadio
    }

    fun parseFromRadio(buf: ByteArray): FromRadio {
        var result: FromRadio = FromRadio.Other
        Reader(buf).forEach { field, r ->
            result = when (field) {
                2 -> parsePacket(r.bytes()) ?: FromRadio.Other
                3 -> FromRadio.MyInfo(Reader(r.bytes()).firstVarint(1) ?: 0L)
                5 -> parseConfig(r.bytes())
                7 -> FromRadio.ConfigComplete(r.varint())
                8 -> { r.varint(); FromRadio.Rebooted }
                10 -> parseChannel(r.bytes())
                11 -> parseQueueStatus(r.bytes())
                else -> { r.skip(); result }
            }
        }
        return result
    }

    private fun parsePacket(buf: ByteArray): FromRadio.Packet? {
        var from = 0L; var to = 0L; var channel = 0
        var portnum = -1; var payload: ByteArray? = null
        Reader(buf).forEach { field, r ->
            when (field) {
                1 -> from = r.fixed32()
                2 -> to = r.fixed32()
                3 -> channel = r.varint().toInt()
                4 -> Reader(r.bytes()).forEach { f, d ->
                    when (f) {
                        1 -> portnum = d.varint().toInt()
                        2 -> payload = d.bytes()
                        else -> d.skip()
                    }
                }
                else -> r.skip()
            }
        }
        val p = payload ?: return null
        return FromRadio.Packet(from, to, channel, portnum, p)
    }

    private fun parseConfig(buf: ByteArray): FromRadio {
        var out: FromRadio = FromRadio.Other
        Reader(buf).forEach { field, r ->
            if (field == 6) {
                var usePreset = false; var preset = 0
                Reader(r.bytes()).forEach { f, d ->
                    when (f) {
                        1 -> usePreset = d.varint() != 0L
                        2 -> preset = d.varint().toInt()
                        else -> d.skip()
                    }
                }
                out = FromRadio.LoraConfig(usePreset, preset)
            } else r.skip()
        }
        return out
    }

    private fun parseChannel(buf: ByteArray): FromRadio.Channel {
        var index = 0; var name = ""; var role = 0
        Reader(buf).forEach { field, r ->
            when (field) {
                1 -> index = r.varint().toInt()
                2 -> Reader(r.bytes()).forEach { f, d ->
                    if (f == 3) name = d.bytes().decodeToString() else d.skip()
                }
                3 -> role = r.varint().toInt()
                else -> r.skip()
            }
        }
        return FromRadio.Channel(index, name, role)
    }

    private fun parseQueueStatus(buf: ByteArray): FromRadio.QueueStatus {
        var free = 0; var maxlen = 0
        Reader(buf).forEach { field, r ->
            when (field) {
                2 -> free = r.varint().toInt()
                3 -> maxlen = r.varint().toInt()
                else -> r.skip()
            }
        }
        return FromRadio.QueueStatus(free, maxlen)
    }

    // -- wire format -----------------------------------------------------------

    class Writer {
        private val out = ByteArrayOutputStream()
        private fun rawVarint(v: Long) {
            var x = v
            while (x and 0x7FL.inv() != 0L) {
                out.write(((x and 0x7F) or 0x80).toInt())
                x = x ushr 7
            }
            out.write(x.toInt())
        }
        private fun key(field: Int, wire: Int) = rawVarint(((field shl 3) or wire).toLong())
        fun varint(field: Int, v: Long) { key(field, 0); rawVarint(v) }
        fun fixed32(field: Int, v: Long) {
            key(field, 5)
            for (i in 0 until 4) out.write(((v ushr (8 * i)) and 0xFF).toInt())
        }
        fun bytes(field: Int, b: ByteArray) { key(field, 2); rawVarint(b.size.toLong()); out.write(b) }
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    class Reader(private val buf: ByteArray) {
        private var pos = 0
        private var wire = 0

        /** Calls [block] for every field; [block] must consume it (read or [skip]). */
        fun forEach(block: (field: Int, r: Reader) -> Unit) {
            while (pos < buf.size) {
                val k = rawVarint()
                wire = (k and 7).toInt()
                block((k ushr 3).toInt(), this)
            }
        }

        fun firstVarint(field: Int): Long? {
            var v: Long? = null
            forEach { f, r -> if (f == field && v == null) v = r.varint() else r.skip() }
            return v
        }

        private fun rawVarint(): Long {
            var shift = 0; var result = 0L
            while (true) {
                require(pos < buf.size) { "truncated varint" }
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "varint too long" }
            }
        }

        fun varint(): Long { require(wire == 0) { "expected varint" }; return rawVarint() }
        fun fixed32(): Long {
            require(wire == 5 && pos + 4 <= buf.size) { "bad fixed32" }
            var v = 0L
            for (i in 0 until 4) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += 4
            return v
        }
        fun bytes(): ByteArray {
            require(wire == 2) { "expected length-delimited" }
            val n = rawVarint().toInt()
            require(n >= 0 && pos + n <= buf.size) { "truncated bytes" }
            return buf.copyOfRange(pos, pos + n).also { pos += n }
        }
        fun skip() {
            when (wire) {
                0 -> rawVarint()
                1 -> pos += 8
                2 -> bytes()
                5 -> pos += 4
                else -> throw IllegalArgumentException("unsupported wire type $wire")
            }
            require(pos <= buf.size) { "truncated field" }
        }
    }
}

/**
 * Serial/TCP stream framing of the Meshtastic API: 0x94 0xC3, big-endian u16
 * length, protobuf. Bytes between frames (firmware debug text) are skipped.
 */
class MeshStreamDeframer(private val onFrame: (ByteArray) -> Unit) {
    private var state = 0
    private var len = 0
    private var buf = ByteArray(0)
    private var have = 0

    fun feed(data: ByteArray, n: Int = data.size) {
        for (i in 0 until n) push(data[i])
    }

    private fun push(b: Byte) {
        val v = b.toInt() and 0xFF
        when (state) {
            0 -> if (v == START1) state = 1
            1 -> state = if (v == START2) 2 else if (v == START1) 1 else 0
            2 -> { len = v shl 8; state = 3 }
            3 -> {
                len = len or v
                if (len == 0 || len > MAX_LEN) { state = 0; return }
                buf = ByteArray(len); have = 0; state = 4
            }
            4 -> {
                buf[have++] = b
                if (have == len) { state = 0; onFrame(buf) }
            }
        }
    }

    companion object {
        const val START1 = 0x94
        const val START2 = 0xC3
        const val MAX_LEN = 512

        fun frame(payload: ByteArray): ByteArray {
            require(payload.size <= MAX_LEN) { "frame too large" }
            return byteArrayOf(START1.toByte(), START2.toByte(), (payload.size shr 8).toByte(), payload.size.toByte()) + payload
        }
    }
}
