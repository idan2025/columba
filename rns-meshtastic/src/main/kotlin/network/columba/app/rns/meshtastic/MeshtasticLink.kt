package network.columba.app.rns.meshtastic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A connection to a Meshtastic node's client API, carrying raw ToRadio /
 * FromRadio protobufs. [open] blocks until usable or throws; afterwards
 * [onFromRadio] gets every message and [onClosed] fires once when the link dies.
 */
interface MeshtasticLink {
    val description: String
    fun open(onFromRadio: (ByteArray) -> Unit, onClosed: (Throwable?) -> Unit)
    /** Blocking; throws when the link is down. */
    fun write(toRadio: ByteArray)
    fun close()
}

/** Wi-Fi / Ethernet nodes: TCP to the node's API port (4403), stream framing. */
class MeshtasticTcpLink(private val host: String, private val port: Int) : MeshtasticLink {
    override val description = "$host:$port"
    @Volatile private var socket: Socket? = null

    override fun open(onFromRadio: (ByteArray) -> Unit, onClosed: (Throwable?) -> Unit) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        s.tcpNoDelay = true
        socket = s
        val deframer = MeshStreamDeframer(onFromRadio)
        thread(name = "meshtastic-tcp-rx", isDaemon = true) {
            var err: Throwable? = null
            try {
                val buf = ByteArray(1024)
                val input = s.getInputStream()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    deframer.feed(buf, n)
                }
            } catch (e: Exception) {
                err = e
            }
            runCatching { s.close() }
            onClosed(err ?: IOException("node closed the connection"))
        }
    }

    override fun write(toRadio: ByteArray) {
        val s = socket ?: throw IOException("not connected")
        synchronized(this) { s.getOutputStream().apply { write(MeshStreamDeframer.frame(toRadio)); flush() } }
    }

    override fun close() { runCatching { socket?.close() }; socket = null }

    private companion object { const val CONNECT_TIMEOUT_MS = 10_000 }
}

/**
 * A node plugged in over USB (OTG): the serial API, framed like TCP. [openStreams]
 * opens the port (Columba's USB host bridge) and may block on the permission prompt.
 */
class MeshtasticUsbLink(
    override val description: String,
    private val openStreams: () -> Pair<InputStream, OutputStream>,
) : MeshtasticLink {
    @Volatile private var streams: Pair<InputStream, OutputStream>? = null

    override fun open(onFromRadio: (ByteArray) -> Unit, onClosed: (Throwable?) -> Unit) {
        val s = openStreams()
        streams = s
        val (input, output) = s
        // Wake the serial API (as the meshtastic Python client does), then let it settle.
        runCatching { output.write(ByteArray(32) { 0xC3.toByte() }); output.flush() }
        Thread.sleep(100)
        val deframer = MeshStreamDeframer(onFromRadio)
        thread(name = "meshtastic-usb-rx", isDaemon = true) {
            var err: Throwable? = null
            try {
                val buf = ByteArray(1024)
                while (true) {
                    val n = input.read(buf, 0, buf.size)
                    if (n < 0) break
                    deframer.feed(buf, n)
                }
            } catch (e: Exception) {
                err = e
            }
            closeStreams(s)
            onClosed(err ?: IOException("USB device disconnected"))
        }
    }

    override fun write(toRadio: ByteArray) {
        val (_, output) = streams ?: throw IOException("not connected")
        synchronized(this) { output.write(MeshStreamDeframer.frame(toRadio)); output.flush() }
    }

    override fun close() { streams?.let(::closeStreams); streams = null }

    private fun closeStreams(s: Pair<InputStream, OutputStream>) {
        runCatching { s.first.close() }
        runCatching { s.second.close() }
    }
}

/**
 * Bluetooth LE to a paired node: write ToRadio; on a FromNum notification (and
 * after each write, since the firmware withholds notifications during the
 * config handshake) read FromRadio until it comes back empty. GATT allows one
 * operation at a time, so every operation goes through [op].
 */
@SuppressLint("MissingPermission")
class MeshtasticBleLink(private val context: Context, private val address: String) : MeshtasticLink {
    override val description = address

    @Volatile private var gatt: BluetoothGatt? = null
    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadio: BluetoothGattCharacteristic? = null
    @Volatile private var pending: CompletableFuture<Any?>? = null
    private val opLock = Object()
    private val drainSignal = LinkedBlockingQueue<Unit>()
    // Per-connection state, reset by every [open]: the interface reuses this
    // link object for each reconnect.
    @Volatile private var closed = false
    @Volatile private var session = 0
    private var onClosed: ((Throwable?) -> Unit)? = null
    @Volatile private var connected = CompletableFuture<Unit>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) return // a previous connection's late callback
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val e = IOException("Bluetooth disconnected (status $status)")
                connected.completeExceptionally(e)
                pending?.completeExceptionally(e)
                fail(e, session)
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { pending?.complete(mtu) }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { complete(status, Unit) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { complete(status, Unit) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { complete(status, Unit) }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            complete(status, value)
        }
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) @Suppress("DEPRECATION") complete(status, c.value ?: ByteArray(0))
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            drainSignal.put(Unit)
        }
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) drainSignal.put(Unit)
        }

        private fun complete(status: Int, value: Any?) {
            val p = pending ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) p.complete(value)
            else p.completeExceptionally(IOException("GATT error $status"))
        }
    }

    override fun open(onFromRadio: (ByteArray) -> Unit, onClosed: (Throwable?) -> Unit) {
        val mySession = ++session
        closed = false
        connected = CompletableFuture()
        pending = null
        drainSignal.clear()
        this.onClosed = onClosed
        val g = connectGatt()
        gatt = g
        try {
            connected.get(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            setUp(g)
        } catch (e: IOException) {
            close()
            throw e
        } catch (e: Exception) {
            close()
            throw IOException(e.cause?.message ?: e.message ?: "Bluetooth connect failed", e)
        }
        startReader(mySession, onFromRadio)
    }

    private fun connectGatt(): BluetoothGatt {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: linkError("this phone has no Bluetooth")
        if (!adapter.isEnabled) linkError("Bluetooth is off")
        val device = adapter.getRemoteDevice(address.uppercase())
        return device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: linkError("could not start a Bluetooth connection")
    }

    /** MTU, service discovery, characteristics and FromNum notifications. */
    private fun setUp(g: BluetoothGatt) {
        // Large MTU so a whole FromRadio fits one read (Meshtastic asks for 512 too).
        runCatching { op { g.requestMtu(512) } }
        op { g.discoverServices() }
        val svc = g.getService(SERVICE) ?: linkError("not a Meshtastic node (service missing)")
        toRadio = svc.getCharacteristic(TO_RADIO) ?: linkError("ToRadio missing")
        fromRadio = svc.getCharacteristic(FROM_RADIO) ?: linkError("FromRadio missing")
        val fromNum = svc.getCharacteristic(FROM_NUM) ?: return
        g.setCharacteristicNotification(fromNum, true)
        val d = fromNum.getDescriptor(CCCD) ?: return
        op {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
        }
    }

    /** Drain FromRadio on each FromNum notification (and every few seconds regardless). */
    private fun startReader(mySession: Int, onFromRadio: (ByteArray) -> Unit) {
        thread(name = "meshtastic-ble-rx", isDaemon = true) {
            while (!closed && session == mySession) {
                // Poll now and then even without a notification: cheap, and robust
                // against a missed FromNum.
                drainSignal.poll(DRAIN_POLL_S, TimeUnit.SECONDS)
                if (closed || session != mySession) break
                try {
                    drainFromRadio(mySession, onFromRadio)
                } catch (e: Exception) {
                    if (!closed) Log.w(TAG, "FromRadio read failed", e)
                    fail(e, mySession)
                }
            }
        }
    }

    private fun drainFromRadio(mySession: Int, onFromRadio: (ByteArray) -> Unit) {
        while (!closed && session == mySession) {
            val bytes = op { gatt?.readCharacteristic(fromRadio) == true } as ByteArray
            if (bytes.isEmpty()) return
            onFromRadio(bytes)
        }
    }

    override fun write(toRadio: ByteArray) {
        val g = gatt ?: throw IOException("not connected")
        val c = this.toRadio ?: throw IOException("not connected")
        op {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, toRadio, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                c.value = toRadio
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
        drainSignal.put(Unit)
    }

    /** Run one GATT operation and wait for its callback. */
    private fun op(start: () -> Boolean): Any? = synchronized(opLock) {
        if (closed) throw IOException("link closed")
        val f = CompletableFuture<Any?>()
        pending = f
        try {
            if (!start()) throw IOException("Bluetooth busy or disconnected")
            f.get(OP_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } catch (e: java.util.concurrent.TimeoutException) {
            throw IOException("Bluetooth operation timed out", e)
        } finally {
            pending = null
        }
    }

    private fun fail(e: Throwable, failedSession: Int) {
        if (closed || failedSession != session) return
        close()
        onClosed?.invoke(e)
    }

    override fun close() {
        closed = true
        drainSignal.put(Unit)
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    private companion object {
        const val TAG = "MeshtasticBleLink"
        const val CONNECT_TIMEOUT_S = 20L
        const val OP_TIMEOUT_S = 10L
        const val DRAIN_POLL_S = 2L
        val SERVICE: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val TO_RADIO: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val FROM_RADIO: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val FROM_NUM: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

private fun linkError(message: String): Nothing = throw IOException(message)
