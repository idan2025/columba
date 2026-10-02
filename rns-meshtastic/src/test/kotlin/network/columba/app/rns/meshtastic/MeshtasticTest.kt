package network.columba.app.rns.meshtastic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MeshtasticTest {

    private fun drain(t: RnsTunnel) = generateSequence { t.next() }.toList()

    @Test fun `fragments match the Python PacketHandler byte for byte`() {
        // Python: num = 400 // 200 + 1 = 3; size = 400 // 3 + 1 = 134 -> 134, 134, 132.
        val data = ByteArray(400) { it.toByte() }
        val t = RnsTunnel()
        t.send(data)
        val out = drain(t)
        assertEquals(listOf(136, 136, 134), out.map { it.payload.size })
        assertEquals(listOf(0 to 1, 0 to 2, 0 to -3), out.map { RnsTunnel.header(it.payload, 0) })
        assertEquals(0xFF.toByte(), RnsTunnel(1).let { it.send(ByteArray(1)); it.next()!!.payload[1] }) // single = pos -1
        assertEquals(MeshProto.BROADCAST, out[0].dest)
        assertTrue(out.all { it.payload.size <= MeshProto.DATA_PAYLOAD_LEN })
    }

    /** py_frags.txt: fragments the Python PacketHandler produced for the same
     *  inputs (the output of `PacketHandler(data, n % 256)`, keys in send order). */
    @Test fun `fragments are identical to the Python implementation's`() {
        val lines = javaClass.getResource("/py_frags.txt")!!.readText().trim().lines()
        for (line in lines) {
            val (n, hex) = line.split(':')
            val size = n.toInt()
            val data = ByteArray(size) { ((it * 7 + size) % 256).toByte() }
            val t = RnsTunnel()
            repeat(size % 256) { t.send(ByteArray(1)); drain(t) }   // advance to index n % 256
            t.send(data)
            val ours = drain(t).joinToString(",") { it.payload.toHexString() }
            assertEquals("size $size", hex, ours)
        }
    }

    @Test fun `round trip, index wraps, out-of-order completes`() {
        val tx = RnsTunnel(); val rx = RnsTunnel()
        repeat(300) { n ->
            val data = Random(n).nextBytes(1 + n % RnsTunnel.HW_MTU)
            tx.send(data)
            val got = drain(tx).mapNotNull { rx.receive(7, it.payload) }
            assertEquals(1, got.size)
            assertArrayEquals(data, got.single())
        }
        // Last fragment first, then the rest: still assembles once complete.
        val data = Random(1).nextBytes(500)
        tx.send(data)
        val frags = drain(tx)
        val fresh = RnsTunnel()
        assertNull(fresh.receive(9, frags.last().payload))
        assertNull(fresh.receive(9, frags[0].payload))
        assertArrayEquals(data, fresh.receive(9, frags[1].payload))
    }

    @Test fun `a gap triggers a REQ and the sender resends that fragment`() {
        val tx = RnsTunnel(); val rx = RnsTunnel()
        val data = Random(2).nextBytes(500)            // 3 fragments
        tx.send(data)
        val frags = drain(tx)
        rx.receive(5, frags[0].payload)                 // expects (0, 2) next
        rx.receive(5, frags[2].payload)                 // gap -> REQ for (0, 2)
        val req = rx.next()!!
        assertEquals("REQ", req.payload.copyOfRange(0, 3).decodeToString())
        assertEquals(0 to 2, RnsTunnel.header(req.payload, 3))
        assertNull(tx.receive(5, req.payload))          // sender queues the resend
        val resend = tx.next()!!
        assertArrayEquals(frags[1].payload, resend.payload)
        assertArrayEquals(data, rx.receive(5, resend.payload))
    }

    @Test fun `link replies go unicast to the node the link came from`() {
        val tx = RnsTunnel(); val rx = RnsTunnel()
        val linkPacket = ByteArray(60).also { it[0] = 0b0000_1100; for (i in 2 until 18) it[i] = i.toByte() }
        tx.send(linkPacket)
        drain(tx).forEach { rx.receive(0x1234, it.payload) }
        rx.send(linkPacket.copyOf())
        assertEquals(0x1234L, rx.next()!!.dest)
    }

    @Test fun `protobuf packet round trip and FromRadio variants`() {
        val payload = byteArrayOf(1, 2, 3)
        val toRadio = MeshProto.toRadioPacket(MeshProto.BROADCAST, 2, MeshProto.PORT_RETICULUM_TUNNEL, payload, id = -5, hopLimit = 1)
        // Re-wrap the inner MeshPacket as FromRadio.packet (field 2) and parse it back.
        val inner = MeshProto.Reader(toRadio).let { r -> var b = ByteArray(0); r.forEach { _, f -> b = f.bytes() }; b }
        val fromRadio = MeshProto.Writer().apply { varint(1, 99); bytes(2, inner) }.toByteArray()
        val p = MeshProto.parseFromRadio(fromRadio) as MeshProto.FromRadio.Packet
        assertEquals(MeshProto.BROADCAST, p.to)
        assertEquals(2, p.channel)
        assertEquals(76, p.portnum)
        assertArrayEquals(payload, p.payload)

        val lora = MeshProto.Writer().apply { varint(1, 1); varint(2, 8) }.toByteArray()
        val cfg = MeshProto.Writer().apply { bytes(6, lora) }.toByteArray()
        assertEquals(MeshProto.FromRadio.LoraConfig(true, 8), MeshProto.parseFromRadio(MeshProto.Writer().apply { bytes(5, cfg) }.toByteArray()))
        val settings = MeshProto.Writer().apply { bytes(2, byteArrayOf(1)); bytes(3, "RNS".encodeToByteArray()) }.toByteArray()
        val ch = MeshProto.Writer().apply { varint(1, 1); bytes(2, settings); varint(3, 2) }.toByteArray()
        assertEquals(MeshProto.FromRadio.Channel(1, "RNS", 2), MeshProto.parseFromRadio(MeshProto.Writer().apply { bytes(10, ch) }.toByteArray()))
        assertEquals(MeshProto.FromRadio.ConfigComplete(42), MeshProto.parseFromRadio(MeshProto.Writer().apply { varint(7, 42) }.toByteArray()))
        val my = MeshProto.Writer().apply { varint(1, 0xDEADBEEF) }.toByteArray()
        assertEquals(MeshProto.FromRadio.MyInfo(0xDEADBEEF), MeshProto.parseFromRadio(MeshProto.Writer().apply { bytes(3, my) }.toByteArray()))
    }

    @Test fun `stream deframer skips noise and splits frames`() {
        val got = mutableListOf<ByteArray>()
        val d = MeshStreamDeframer { got += it }
        val stream = "boot log\r\n".encodeToByteArray() + MeshStreamDeframer.frame(byteArrayOf(1, 2)) +
            byteArrayOf(0x94.toByte()) + MeshStreamDeframer.frame(byteArrayOf(3))
        stream.forEach { d.feed(byteArrayOf(it)) }
        assertEquals(listOf(listOf<Byte>(1, 2), listOf<Byte>(3)), got.map { it.toList() })
    }
}
