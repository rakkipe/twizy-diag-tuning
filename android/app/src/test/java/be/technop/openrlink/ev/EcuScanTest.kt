package be.technop.openrlink.ev

import be.technop.openrlink.elm.Elm327
import be.technop.openrlink.transport.Transport
import be.technop.openrlink.transport.TransportTimeoutException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EcuScanTest {
    private class Fake(private val replies: Map<String, String> = emptyMap(), private val timeoutAt: String? = null) : Transport {
        val commands = mutableListOf<String>()
        override val name = "test"
        override val isOpen = true
        override fun open() {}
        override fun close() {}
        override fun write(bytes: ByteArray) { commands.add(String(bytes).trim()) }
        override fun readUntilPrompt(timeoutMs: Long): String {
            if (commands.last() == timeoutAt) throw TransportTimeoutException("test timeout")
            return replies[commands.last()] ?: if (commands.last().startsWith("AT")) "OK" else "NO DATA"
        }
    }
    private val module = EcuModule(EcuTarget("Synthetic ABS", "740", "760"))
    private fun syntheticZip(file: java.io.File, records: Map<String, Pair<JSONObject, JSONObject>>) {
        ZipOutputStream(file.outputStream()).use { z ->
            val index = JSONObject()
            records.forEach { (name, pair) ->
                index.put(name, pair.first)
                z.putNextEntry(ZipEntry(name)); z.write(pair.second.toString().toByteArray()); z.closeEntry()
            }
            z.putNextEntry(ZipEntry("db.json")); z.write(index.toString().toByteArray()); z.closeEntry()
        }
    }
    private fun definition(project: String, tx: String = "740", rx: String = "760", description: String = "Synthetic fault"): Pair<JSONObject, JSONObject> {
        val meta = JSONObject("""{"projects":["$project"],"group":"Synthetic ABS","protocol":"CAN","ecuname":"Synthetic ECU","autoidents":[{"diagnostic_version":"1","supplier_code":"TST","soft_version":"0100","version":"0001"}]}""")
        val body = JSONObject("""{"obd":{"protocol":"CAN","send_id":"$tx","recv_id":"$rx"},"data":{"DTCDeviceIdentifier":{"bitscount":16,"lists":{"4660":"$description"}}}}""")
        return meta to body
    }

    @Test fun comprehensiveListsContainOnlyValidPhysicalTargets() {
        assertEquals(15, EcuModules.builtIn(EvVehicle.ZOE_PH1).size)
        assertEquals(27, EcuModules.builtIn(EvVehicle.ZOE_PH2).size)
        assertEquals(16, EcuModules.builtIn(EvVehicle.LEAF_24_30).size)
        assertEquals(15, EcuModules.builtIn(EvVehicle.LEAF_40_62).size)
        for (v in EvVehicle.entries) {
            val list = EcuModules.builtIn(v)
            assertTrue(list.all { EcuModules.validPair(it.target.tx, it.target.rx) })
            assertEquals(list.size, list.distinctBy { it.id }.size)
        }
        assertFalse(EcuModules.validPair("7DF", "7E8"))
        assertFalse(EcuModules.validPair("800", "801"))
        assertFalse(EcuModules.validPair("700", "700"))
        assertFalse(EcuModules.builtIn(EvVehicle.LEAF_40_62).any { it.target.tx == "79D" })
    }

    @Test fun missingAndMalformedResponsesNeverBecomeCleanScan() {
        val missing = EcuScanner(Elm327(Fake())).scan(module, EvVehicle.LEAF_24_30, false)
        assertTrue(missing.state.contains("geen passend antwoord"))
        val malformed = EcuScanner(Elm327(Fake(mapOf("1902FF" to "59 02 FF 12 34")))).scan(module, EvVehicle.LEAF_24_30, false)
        assertTrue(malformed.state.contains("niet volledig uitgelezen"))
        assertNull(ModuleDtcDecoder.uds(listOf(0x59, 2, 255, 1)))
        assertNull(ModuleDtcDecoder.kwp(listOf(0x57, 2, 0x90, 7, 0x41)))
    }

    @Test fun repliesToOtherReadServicesStillProveReachability() {
        val t = Fake(mapOf("22F187" to "7F 22 31"))
        val result = EcuScanner(Elm327(t)).scan(module, EvVehicle.LEAF_24_30, false)
        assertTrue(result.state.contains("module antwoordt"))
        assertTrue(result.state.contains("niet volledig uitgelezen"))
    }

    @Test fun selfTestStatusDoesNotMeanActiveFaultAndKwpKeepsItsOwnStatus() {
        val t = Fake(mapOf("1902FF" to "59 02 FF 12 34 01 50 56 78 02 09"))
        val result = EcuScanner(Elm327(t)).scan(module, EvVehicle.ZOE_PH1, false)
        assertEquals(2, result.dtcs.size)
        assertFalse(result.dtcs[0].faultIndicated)
        assertTrue(result.dtcs[1].faultIndicated)
        assertTrue(result.state.contains("1 met foutindicatie"))
        val kwp = ModuleDtcDecoder.kwp(listOf(0x57, 1, 0xD1, 0x13, 0xE0))!!.single()
        assertEquals("U1113", kwp.code)
        assertFalse(kwp.udsStatus)
    }

    @Test fun legacyFallbackOnlyAfterExplicitUnsupportedReply() {
        val t = Fake(mapOf("1902FF" to "7F 19 11", "17FF00" to "57 01 D1 13 E0"))
        val result = EcuScanner(Elm327(t)).scan(module, EvVehicle.LEAF_24_30, false)
        assertEquals("U1113", result.dtcs.single().code)
        assertTrue(result.state.startsWith("KWP"))
        val absent = Fake()
        EcuScanner(Elm327(absent)).scan(module, EvVehicle.LEAF_24_30, false)
        assertFalse(absent.commands.contains("17FF00"))
    }

    @Test fun sessionFallbackRequiresNegativeSessionStatusAndRestoresDefault() {
        val t = Fake(mapOf("1902FF" to "7F 19 7F", "1003" to "50 03", "1001" to "50 01"))
        EcuScanner(Elm327(t)).scan(module.copy(session = 3), EvVehicle.ZOE_PH2, false)
        assertTrue(t.commands.contains("1003")); assertEquals("1001", t.commands.last())
        val positive = Fake(mapOf("1902FF" to "59 02 7F"))
        EcuScanner(Elm327(positive)).scan(module.copy(session = 3), EvVehicle.ZOE_PH2, false)
        assertFalse(positive.commands.any { it.startsWith("10") })
        try { module.copy(session = 2, sessionRequired = true); fail("programming session rejected") } catch (_: IllegalArgumentException) {}
    }

    @Test fun alternateDtcMasksMustComeFromMetadataAndOnlyFollowRangeRejection() {
        val t = Fake(mapOf("1902FF" to "7F 19 31", "19020F" to "59 02 0F"))
        val result = EcuScanner(Elm327(t)).scan(module.copy(readMasks = listOf(15)), EvVehicle.ZOE_PH1, false)
        assertTrue(t.commands.contains("19020F"))
        assertTrue(result.state.contains("geen DTC-records"))
        val absent = Fake()
        EcuScanner(Elm327(absent)).scan(module.copy(readMasks = listOf(15)), EvVehicle.ZOE_PH1, false)
        assertFalse(absent.commands.contains("19020F"))
    }

    @Test fun requiredReadSessionIsAlwaysClosedIncludingCancellation() {
        val spec = module.copy(sessionRequired = true, session = 0xC0)
        val t = Fake(mapOf("10C0" to "50 C0", "1081" to "50 81", "1902FF" to "59 02 FF"))
        var steps = 0
        try {
            EcuScanner(Elm327(t)).scan(spec, EvVehicle.ZOE_PH1, true) { if (++steps == 3) throw IllegalStateException("stopped") }
            fail("must stop")
        } catch (ex: IllegalStateException) { assertEquals("stopped", ex.message) }
        assertEquals("1081", t.commands.last())
        assertTrue(t.commands.filterNot { it.startsWith("AT") }.all { it.startsWith("19") || it.startsWith("22") || it in listOf("10C0", "1081") })
    }

    @Test fun adapterTimeoutAbortsInsteadOfMixingLateResponses() {
        val t = Fake(timeoutAt = "1902FF")
        try { EcuScanner(Elm327(t)).scan(module, EvVehicle.LEAF_40_62, true); fail("must stop") }
        catch (_: TransportTimeoutException) {}
        assertEquals("1902FF", t.commands.last())
    }

    @Test fun databaseSeparatesVehicleProjectsAndRejectsVirtualOrBroadcastIds() {
        val dir = Files.createTempDirectory("ecu-synthetic").toFile()
        try {
            val file = java.io.File(dir, "ecu.zip")
            syntheticZip(file, mapOf("a.json" to definition("x10"), "a2.json" to definition("x10"), "b.json" to definition("X10Ph2", "18DADBF1", "18DAF1DB"), "bad.json" to definition("x10", "7DF", "7E8")))
            val db = EcuDatabase({ listOf(file) }, file)
            val ph1 = db.modules(EvVehicle.ZOE_PH1)
            assertEquals(1, ph1.size); assertEquals(2, ph1.single().variants.size)
            assertEquals("18DADBF1", db.modules(EvVehicle.ZOE_PH2).single().target.tx)
            assertTrue(db.modules(EvVehicle.LEAF_24_30).isEmpty())
            assertEquals(15, EcuModules.merge(EvVehicle.ZOE_PH1, ph1).size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun descriptionsRequireExactFullIdentificationAndMustAgree() {
        val dir = Files.createTempDirectory("ecu-synthetic").toFile()
        try {
            val file = java.io.File(dir, "ecu.zip")
            syntheticZip(file, mapOf("a.json" to definition("x10")))
            val db = EcuDatabase({ listOf(file) }, file)
            val spec = db.modules(EvVehicle.ZOE_PH1).single()
            assertTrue(db.matchingVariants(spec, EcuIdentity("1", "TST", "0100", "")).isEmpty())
            assertTrue(db.matchingVariants(spec, EcuIdentity("1", "TST", "0100", "9999")).isEmpty())
            val match = db.matchingVariants(spec, EcuIdentity("1", "TST", "0100", "0001"))
            assertEquals("Synthetic fault", db.lookup(match, "123456"))
            assertNull(db.lookup(emptyList(), "123456"))
            assertNull(db.lookup(match, "341256")) // no byte-swap guessing
            syntheticZip(file, mapOf("a.json" to definition("x10"), "b.json" to definition("x10", description = "Conflicting fault")))
            db.reset()
            val all = db.matchingVariants(db.modules(EvVehicle.ZOE_PH1).single(), EcuIdentity("1", "TST", "0100", "0001"))
            assertNull(db.lookup(all, "123456"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun invalidImportPreservesTheExistingDatabase() {
        val dir = Files.createTempDirectory("ecu-synthetic").toFile()
        try {
            val file = java.io.File(dir, "ecu.zip")
            syntheticZip(file, mapOf("a.json" to definition("x10")))
            val original = file.readBytes()
            val db = EcuDatabase({ listOf(file) }, file)
            assertFalse(db.importFrom("not a zip".byteInputStream()))
            assertArrayEquals(original, file.readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test fun optionalUserArchiveContainsReadableZoeCatalogs() {
        val path = System.getenv("OPENRLINK_TEST_ECU_ZIP")
        org.junit.Assume.assumeTrue("No personal database supplied", path != null)
        val file = java.io.File(path!!)
        val db = EcuDatabase({ listOf(file) }, java.io.File(file.parentFile, "unused-import-destination.zip"))
        val ph1 = EcuModules.merge(EvVehicle.ZOE_PH1, db.modules(EvVehicle.ZOE_PH1))
        val ph2 = EcuModules.merge(EvVehicle.ZOE_PH2, db.modules(EvVehicle.ZOE_PH2))
        assertTrue(ph1.size >= 15); assertTrue(ph2.size >= 27)
        assertTrue(ph1.all { EcuModules.validPair(it.target.tx, it.target.rx) })
        assertTrue(ph2.all { EcuModules.validPair(it.target.tx, it.target.rx) })
        println("User archive: Zoe PH1 ${ph1.size} modules; ZE50 ${ph2.size} modules")
    }
}
