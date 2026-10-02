package network.columba.app.rns.host.meshtastic

import android.content.Context
import android.util.Log
import com.chaquo.python.PyObject
import kotlinx.coroutines.runBlocking
import network.columba.app.rns.api.annotation.ReflectivelyKept
import network.columba.app.rns.host.usb.KotlinUSBBridge
import network.columba.app.rns.meshtastic.MeshtasticBleLink
import network.columba.app.rns.meshtastic.MeshtasticLink
import network.columba.app.rns.meshtastic.MeshtasticSession
import network.columba.app.rns.meshtastic.MeshtasticTcpLink
import network.columba.app.rns.meshtastic.MeshtasticUsbLink
import java.util.concurrent.ConcurrentHashMap

/**
 * Python-backend seam for Meshtastic: the bundled `ColumbaMeshtasticInterface`
 * (columba_meshtastic_interface.py) drives a [MeshtasticSession] per interface
 * through this bridge, so both backends share the same node links and tunnel.
 *
 * Handed to Python via `event_bridge.set_meshtastic_bridge` before `Reticulum()`
 * is constructed (see `PythonRnsRuntime.start`). Callbacks are Python callables
 * invoked as `callback(bytes)` / `callback(online)` from Kotlin threads.
 */
@ReflectivelyKept
class KotlinMeshtasticBridge private constructor(
    private val context: Context,
) {
    private val sessions = ConcurrentHashMap<String, MeshtasticSession>()

    /**
     * Start (or restart) the session for interface [name]. [usbVendorId] /
     * [usbProductId] are -1 when unset. Returns false when the link can't be built.
     */
    @Suppress("LongParameterList") // Flat primitives: the Chaquopy call site can't pass a config object.
    fun start(
        name: String,
        connectionMode: String,
        deviceAddress: String,
        tcpHost: String,
        tcpPort: Int,
        usbVendorId: Int,
        usbProductId: Int,
        channelIndex: Int,
        hopLimit: Int,
        onPacket: PyObject,
        onOnline: PyObject,
    ): Boolean {
        stop(name)
        val link =
            runCatching { link(connectionMode, deviceAddress, tcpHost, tcpPort, usbVendorId, usbProductId) }
                .getOrElse {
                    Log.e(TAG, "$name: can't set up the $connectionMode link: ${it.message}")
                    return false
                }
        val session =
            MeshtasticSession(
                name = name,
                link = link,
                channelIndex = channelIndex,
                hopLimit = hopLimit,
                onPacket = { data -> onPacket.callAttr("__call__", data) },
                onOnline = { online -> onOnline.callAttr("__call__", online) },
            )
        sessions[name] = session
        session.start()
        return true
    }

    /** Queue an RNS packet on [name]'s session; false when offline or dropped. */
    fun send(
        name: String,
        data: ByteArray,
    ): Boolean = sessions[name]?.send(data) ?: false

    fun stop(name: String) {
        sessions.remove(name)?.stop()
    }

    /** Why [name]'s node is unreachable right now, or null. */
    fun lastError(name: String): String? = sessions[name]?.lastError

    private fun link(
        connectionMode: String,
        deviceAddress: String,
        tcpHost: String,
        tcpPort: Int,
        usbVendorId: Int,
        usbProductId: Int,
    ): MeshtasticLink =
        when (connectionMode) {
            "tcp" -> MeshtasticTcpLink(tcpHost, tcpPort)
            "usb" -> {
                require(usbVendorId >= 0 && usbProductId >= 0) { "no USB device chosen" }
                MeshtasticUsbLink("USB %04x:%04x".format(usbVendorId, usbProductId)) {
                    openUsb(usbVendorId, usbProductId)
                }
            }
            else -> MeshtasticBleLink(context, deviceAddress)
        }

    private fun openUsb(
        vendorId: Int,
        productId: Int,
    ): Pair<java.io.InputStream, java.io.OutputStream> {
        val usb = KotlinUSBBridge.getInstance(context)
        val deviceId = usb.findDeviceByVidPid(vendorId, productId)
        if (deviceId >= 0 && !usb.hasPermission(deviceId)) {
            val granted = runBlocking { usb.requestPermissionSuspend(deviceId) }
            if (!granted) throw SecurityException("USB permission denied for device $deviceId")
        }
        return usb.openSerialStreams(vendorId = vendorId, productId = productId, deviceId = null)
    }

    companion object {
        private const val TAG = "KotlinMeshtasticBridge"

        @Volatile private var instance: KotlinMeshtasticBridge? = null

        fun getInstance(context: Context): KotlinMeshtasticBridge =
            instance ?: synchronized(this) {
                instance ?: KotlinMeshtasticBridge(context.applicationContext).also { instance = it }
            }
    }
}
