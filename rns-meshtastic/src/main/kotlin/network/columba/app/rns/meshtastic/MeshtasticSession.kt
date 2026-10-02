package network.columba.app.rns.meshtastic

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.columba.app.rns.meshtastic.MeshProto.FromRadio
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Reticulum over a Meshtastic node (native port of RNS_Over_Meshtastic): keeps
 * [link] connected (reconnecting with backoff), runs the client-API config
 * handshake and paces tunnel fragments to the node's modem preset. The node is
 * driven directly — the Meshtastic app must not be connected to it at the same time.
 *
 * Whole RNS packets go in through [send] and come out through [onPacket];
 * [onOnline] reports when the node is usable. The node's settings are only read,
 * never written.
 *
 * Traffic stays on [channelIndex] / [portnum] with [hopLimit]: put every RNS
 * node on a secondary channel with its own key so the tunnel never touches the
 * public channel, and keep hops low so it doesn't flood the wider mesh.
 */
class MeshtasticSession(
    private val name: String,
    private val link: MeshtasticLink,
    private val channelIndex: Int,
    private val hopLimit: Int,
    private val portnum: Int = MeshProto.PORT_RETICULUM_TUNNEL,
    private val onPacket: (ByteArray) -> Unit,
    private val onOnline: (Boolean) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tunnel = RnsTunnel()
    private val tunnelLock = Any()

    @Volatile private var myNode = 0L

    @Volatile private var modemPreset: Int? = null

    @Volatile private var radioQueueFree = Int.MAX_VALUE

    @Volatile private var configDone: CompletableFuture<Unit>? = null

    @Volatile private var linkDown: CompletableFuture<Throwable?>? = null

    @Volatile private var lastWrite = 0L

    /** True between a completed handshake and the link dropping. */
    @Volatile var online = false
        private set

    /** Why the node is not reachable right now, or null. */
    @Volatile var lastError: String? = null
        private set

    /** Name of the configured channel as the node reports it ("" = default). */
    @Volatile var channelName: String? = null
        private set

    /** Bytes put on air (fragment payloads). */
    val txBytes = AtomicLong()

    fun start() {
        scope.launch { run() }
    }

    fun stop() {
        scope.cancel()
        linkDown?.complete(null)
        link.close()
        setOnline(false)
    }

    /** Queue an RNS packet. Returns false when it was dropped (offline or queue full). */
    fun send(data: ByteArray): Boolean {
        if (!online) return false
        val ok = synchronized(tunnelLock) { tunnel.send(data) }
        if (!ok) Log.w(TAG, "$name: send queue full, dropped ${data.size} bytes")
        return ok
    }

    private suspend fun run() {
        var backoff = MIN_BACKOFF_MS
        while (scope.isActive) {
            try {
                connectOnce()
                backoff = MIN_BACKOFF_MS
                val down = checkNotNull(linkDown)
                while (!down.isDone && scope.isActive) {
                    pump()
                }
                val cause = down.getNow(null)
                lastError = "connection lost" + (cause?.message?.let { ": $it" } ?: "")
                Log.w(TAG, "$name: link closed", cause)
            } catch (e: Exception) {
                if (!scope.isActive) break
                lastError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "$name: ${link.description}: ${e.message}")
            }
            setOnline(false)
            link.close()
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun connectOnce() {
        val done = CompletableFuture<Unit>()
        val down = CompletableFuture<Throwable?>()
        configDone = done
        linkDown = down
        val nonce = Random.nextInt(1, Int.MAX_VALUE)
        link.open(onFromRadio = { onFromRadio(it, nonce) }, onClosed = { down.complete(it) })
        link.write(MeshProto.toRadioWantConfig(nonce))
        try {
            done.get(CONFIG_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            throw IOException("node did not send its configuration", e)
        }
        val ch =
            channelName ?: throw IOException(
                "channel $channelIndex isn't set up on this node — add a secondary channel there " +
                    "(same name and key on every node), or set the channel index to 0",
            )
        Log.i(
            TAG,
            "$name: connected to node ${"%08x".format(myNode)} via ${link.description}, " +
                "channel $channelIndex '$ch', preset $modemPreset",
        )
        lastError = null
        lastWrite = System.currentTimeMillis()
        setOnline(true)
    }

    /** One pass of the send loop: transmit the next fragment, then wait out the airtime. */
    private suspend fun pump() {
        if (radioQueueFree <= 0) {
            delay(QUEUE_FULL_WAIT_MS)
            return
        }
        val out = synchronized(tunnelLock) { tunnel.next() }
        if (out == null) {
            delay(IDLE_POLL_MS)
            maybeHeartbeat()
            return
        }
        val id = Random.nextInt().let { if (it == 0) 1 else it }
        link.write(MeshProto.toRadioPacket(out.dest, channelIndex, portnum, out.payload, id, hopLimit))
        txBytes.addAndGet(out.payload.size.toLong())
        lastWrite = System.currentTimeMillis()
        delay(RnsTunnel.sendDelayMs(modemPreset))
    }

    private fun maybeHeartbeat() {
        if (System.currentTimeMillis() - lastWrite < HEARTBEAT_MS) return
        lastWrite = System.currentTimeMillis()
        runCatching { link.write(MeshProto.toRadioHeartbeat()) }
    }

    private fun onFromRadio(
        bytes: ByteArray,
        nonce: Int,
    ) {
        val msg =
            runCatching { MeshProto.parseFromRadio(bytes) }.getOrElse {
                Log.w(TAG, "$name: unparsable FromRadio", it)
                return
            }
        when (msg) {
            is FromRadio.MyInfo -> myNode = msg.nodeNum
            is FromRadio.LoraConfig -> modemPreset = if (msg.usePreset) msg.modemPreset else null
            is FromRadio.Channel ->
                if (msg.index == channelIndex) {
                    channelName = if (msg.role == MeshProto.ROLE_DISABLED) null else msg.name
                }
            is FromRadio.QueueStatus -> radioQueueFree = msg.free
            is FromRadio.ConfigComplete -> if (msg.nonce == (nonce.toLong() and 0xFFFFFFFFL)) configDone?.complete(Unit)
            is FromRadio.Rebooted -> linkDown?.complete(IOException("node rebooted"))
            is FromRadio.Packet -> onMeshPacket(msg)
            FromRadio.Other -> Unit
        }
    }

    private fun onMeshPacket(p: FromRadio.Packet) {
        if (p.portnum != portnum || p.channel != channelIndex || p.from == myNode) return
        if (p.to != MeshProto.BROADCAST && p.to != myNode) return
        val data = synchronized(tunnelLock) { tunnel.receive(p.from, p.payload) } ?: return
        onPacket(data)
    }

    private fun setOnline(value: Boolean) {
        if (online == value) return
        online = value
        onOnline(value)
    }

    private companion object {
        const val TAG = "MeshtasticSession"
        const val CONFIG_TIMEOUT_S = 30L
        const val IDLE_POLL_MS = 100L
        const val QUEUE_FULL_WAIT_MS = 250L
        const val HEARTBEAT_MS = 5 * 60_000L
        const val MIN_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}
