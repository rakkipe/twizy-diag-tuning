package be.technop.openrlink.ev

import be.technop.openrlink.elm.Elm327
import be.technop.openrlink.elm.ElmResponse
import be.technop.openrlink.obd.CanDtcDecoder
import be.technop.openrlink.obd.Pids
import be.technop.openrlink.transport.DemoTransport
import be.technop.openrlink.transport.Transport
import be.technop.openrlink.uds.UdsClient
import org.junit.Assert.*
import org.junit.Test

class EvDiagnosticsTest {
    private fun bytes(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16) }
    private class Fake(private val replies: Map<String, String> = emptyMap()) : Transport {
        val commands = mutableListOf<String>()
        override val name = "test"
        override val isOpen = true
        override fun open() {}
        override fun close() {}
        override fun write(bytes: ByteArray) { commands.add(String(bytes).trim()) }
        override fun readUntilPrompt(timeoutMs: Long) = replies[commands.last()] ?: if (commands.last().startsWith("AT")) "OK" else "NO DATA"
    }

    @Test fun parsesSpacedAndCompactCanWithPadding() {
        assertEquals(bytes("62 90 02 1D 4C"), ElmResponse.messages("18DAF1DB 05 62 90 02 1D 4C AA AA", "18DAF1DB").single().data)
        assertEquals(bytes("62 90 02 1D 4C"), ElmResponse.messages("18DAF1DB056290021D4CAAAA", "18DAF1DB").single().data)
        assertEquals(bytes("59 02 FF"), ElmResponse.messages("7BB035902FF00000000", "7BB").single().data)
    }

    @Test fun reassemblesLongLeafCellsWithSequenceWrapAndOtherEcus() {
        val pdu = listOf(0x61, 2) + List(196) { it and 255 }
        val raw = buildString {
            appendLine("7BB 10 C6 " + pdu.take(6).joinToString(" ") { "%02X".format(it) })
            var sequence = 1
            for (part in pdu.drop(6).chunked(7)) {
                appendLine("7BC 03 59 02 FF 00 00 00 00")
                appendLine("7BB %02X ".format(0x20 or sequence) + part.joinToString(" ") { "%02X".format(it) })
                sequence = (sequence + 1) and 15
            }
        }
        assertEquals(pdu, ElmResponse.messages(raw, "7BB").single().data)
        assertTrue(ElmResponse.messages(raw.replace("7BB 22 ", "7BB 24 "), "7BB").isEmpty())
        assertTrue(ElmResponse.messages(raw.substringBeforeLast("7BB"), "7BB").isEmpty())
    }

    @Test fun indexedElmOutputMustBeCompleteAndOrdered() {
        assertEquals(bytes("61 01 11 22 33 44 55 66"), ElmResponse.messages("008\r0: 61 01 11 22 33 44\r1: 55 66").single().data)
        assertTrue(ElmResponse.messages("008\r0: 61 01 11 22 33 44").isEmpty())
        assertTrue(ElmResponse.messages("008\r0: 61 01 11 22 33 44\r2: 55 66").isEmpty())
        assertTrue(ElmResponse.messages("7BB 03 59 02 FF\rBUFFER FULL").isEmpty())
    }

    @Test fun negativeWrongEcuAndWrongDidCannotBecomeMeasurements() {
        val negative = Fake(mapOf("229002" to "18DAF1DB 03 7F 22 31"))
        val r = UdsClient(Elm327(negative), "18DAF1DB").readDataByIdentifier(0x9002)
        assertFalse(r.ok); assertTrue(r.nrc!!.contains("requestOutOfRange"))
        for (raw in listOf("18DAF1DA 05 62 90 02 1D 4C", "18DAF1DB 05 62 90 03 1D 4C", "NO DATA", "18DAF1DB 10 20 62 90 02 1D 4C 00")) {
            assertFalse(UdsClient(Elm327(Fake(mapOf("229002" to raw))), "18DAF1DB").readDataByIdentifier(0x9002).ok)
        }
    }

    @Test fun ze50Uses29BitAndGenericObdRestoresBroadcastAndFraming() {
        val t = Fake(); val e = Elm327(t)
        e.configUdsTarget("18DADBF1", "18DAF1DB")
        assertTrue(t.commands.containsAll(listOf("ATSP7", "ATSH18DADBF1", "ATCRA18DAF1DB", "ATFCSH18DADBF1")))
        e.configGenericObd()
        assertEquals(listOf("ATSP6", "ATCAF1", "ATH1", "ATS1", "ATFCSM0", "ATCFC1", "ATSH7DF", "ATCRA", "ATAR"), t.commands.takeLast(9))
    }

    @Test fun adapterRejectionStopsTargetConfiguration() {
        val t = Fake(mapOf("ATSP7" to "?"))
        try { Elm327(t).configUdsTarget("18DADBF1", "18DAF1DB"); fail("must reject") } catch (_: IllegalStateException) {}
        assertEquals(listOf("ATSP7"), t.commands)
    }

    @Test fun ze50ScalingAndSkippedCellDids() {
        assertEquals(75.0, EvDecoder.decode(EvVehicle.ZOE_PH2, "229002", bytes("1D4C")).metrics.single().value, 0.001)
        assertEquals(380.0, EvDecoder.decode(EvVehicle.ZOE_PH2, "229005", bytes("0ED8")).metrics.single().value, 0.001)
        assertEquals(5.0, EvDecoder.decode(EvVehicle.ZOE_PH2, "22925D", bytes("7EE0")).metrics.single().value, 0.001)
        assertEquals(96, EvRequests.zoe50CellDids.size)
        assertEquals(0x9041, EvRequests.zoe50CellDids[31])
        assertEquals(0x9061, EvRequests.zoe50CellDids[62])
        assertEquals(0x9083, EvRequests.zoe50CellDids.last())
        assertEquals(4.0, EvDecoder.decode(EvVehicle.ZOE_PH2, "229041", bytes("1000")).cells[32]!!, 0.001)
    }

    @Test fun leafOlderReferenceCaptureNeverFabricatesSocOrSoh() {
        // Published 39-byte OVMS PollReply_Battery capture, after 61 01.
        val d = bytes("0000020d028700000363ffffffff001c2af89a892e4b03a4005c269a000ecf810009d7b0800001")
        val r = EvDecoder.decode(EvVehicle.LEAF_24_30, "2101", d)
        assertEquals(64.504, r.metrics.first { it.label == "Capaciteit" }.value, 0.001)
        assertEquals(98.82, r.metrics.first { it.label.startsWith("Hx") }.value, 0.001)
        assertFalse(r.metrics.any { it.label == "SOH" || it.label == "SOC" })
        assertTrue(EvDecoder.decode(EvVehicle.LEAF_40_62, "2101", d).metrics.isEmpty())
    }

    @Test fun unknownLayoutsAndImpossibleReadingsStayEmpty() {
        assertTrue(EvDecoder.decode(EvVehicle.ZOE_PH2, "229002", bytes("FFFF")).metrics.isEmpty())
        assertTrue(EvDecoder.decode(EvVehicle.ZOE_PH2, "229002", bytes("1D4C00")).metrics.isEmpty())
        assertTrue(EvDecoder.decode(EvVehicle.LEAF_24_30, "2102", List(195) { 0 }).cells.isEmpty())
        assertTrue(EvDecoder.decode(EvVehicle.ZOE_PH2, "229021", bytes("FFFF")).cells.isEmpty())
        val partial = EvSnapshot(EvVehicle.ZOE_PH1, 0, emptyList(), mapOf(1 to 3.9, 96 to 4.0), emptyList())
        assertNull(partial.cellDeltaMv)
    }

    @Test fun obdMissingOrNegativeReplyIsNotNoFaults() {
        for (raw in listOf("NO DATA", "7E8 03 7F 03 11", "43 02 01 33")) {
            try { CanDtcDecoder.decode(raw); fail("must reject $raw") } catch (_: IllegalArgumentException) {}
        }
        assertEquals(listOf("P0133", "C0101"), CanDtcDecoder.decode("7E8 06 43 02 01 33 41 01 00"))
        assertTrue(CanDtcDecoder.decode("7E8 02 43 00 00 00 00 00 00").isEmpty())
        val rpm = Pids.LIST.first { it.pid == "0C" }
        assertEquals(1726.0, rpm.decode(Pids.parsePayload(rpm, "7E8 04 41 0C 1A F8 00 00 00")!!), 0.001)
    }

    @Test fun allVehicleDemosProduce96CellsAndOnlyReadCommands() {
        for (v in EvVehicle.entries.filterNot { it.isTwizy }) {
            val demo = DemoTransport(v)
            val sent = mutableListOf<String>()
            val transport = object : Transport by demo {
                override fun write(bytes: ByteArray) { sent.add(String(bytes).trim()); demo.write(bytes) }
            }
            val result = EvReader(Elm327(transport)).read(v, true)
            assertEquals(v.label, 96, result.cells.size)
            assertTrue(v.label, result.metrics.isNotEmpty())
            assertNotNull(result.cellDeltaMv)
            assertTrue(sent.filterNot { it.startsWith("AT") }.all { it.matches(Regex("21[0-9A-F]{2}|22[0-9A-F]{4}")) })
        }
    }

    @Test fun absentZe50DoesNotProbe96Dids() {
        val fake = Fake()
        val result = EvReader(Elm327(fake)).read(EvVehicle.ZOE_PH2, true)
        assertEquals(9, fake.commands.count { it.startsWith("22") }) // 6 summary + 3 cells
        assertTrue(result.metrics.isEmpty()); assertTrue(result.cells.isEmpty())
        assertTrue(result.details.last().contains("0/96"))
    }
}
