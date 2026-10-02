package network.columba.app.rns.backend.kt.meshtastic

import network.columba.app.rns.meshtastic.MeshtasticLink
import network.columba.app.rns.meshtastic.MeshtasticSession
import network.columba.app.rns.meshtastic.RnsTunnel
import network.reticulum.interfaces.Interface

/**
 * reticulum-kt Interface over a Meshtastic node: a thin adapter around
 * [MeshtasticSession], which owns the connection, handshake and tunnel.
 */
class MeshtasticInterface(
    name: String,
    link: MeshtasticLink,
    channelIndex: Int,
    hopLimit: Int,
) : Interface(name) {
    override val hwMtu: Int = RnsTunnel.HW_MTU
    override val bitrate: Int = 500

    private val session =
        MeshtasticSession(
            name = name,
            link = link,
            channelIndex = channelIndex,
            hopLimit = hopLimit,
            onPacket = { processIncoming(it) },
            onOnline = { setOnline(it) },
        )

    /** Why the node is not reachable right now, or null. */
    val lastError: String? get() = session.lastError

    override fun start() {
        session.start()
    }

    override fun processOutgoing(data: ByteArray) {
        if (session.send(data)) txBytes.addAndGet(data.size.toLong())
    }

    override fun detach() {
        super.detach()
        session.stop()
    }

    override fun toString(): String = "MeshtasticInterface[$name]"
}
