package be.technop.openrlink.transport

import be.technop.openrlink.ev.EvVehicle
import be.technop.openrlink.ev.EvRequests

/**
 * Simuleert een ELM327 zonder hardware. Handig om de UI/flow te testen
 * (vgl. AndrOBD demo-mode). Geeft plausibele antwoorden op de belangrijkste commando's.
 */
class DemoTransport(private val vehicle: EvVehicle = EvVehicle.TWIZY) : Transport {
    override val name = "Demo (geen adapter)"
    private var open = false
    override val isOpen get() = open
    private var lastCmd = ""

    override fun open() { open = true }
    override fun close() { open = false }

    override fun write(bytes: ByteArray) {
        lastCmd = String(bytes).trim().uppercase()
    }

    override fun readUntilPrompt(timeoutMs: Long): String {
        Thread.sleep(40)
        if (!vehicle.isTwizy && !lastCmd.startsWith("AT") && !lastCmd.startsWith("ST")) return demoEv(lastCmd)
        return when {
            lastCmd.startsWith("ATZ")  -> "ELM327 v1.5"
            lastCmd.startsWith("ATI")  -> "ELM327 v1.5"
            lastCmd.startsWith("AT")   -> "OK"
            lastCmd.startsWith("ST")   -> "OK"
            lastCmd == "0100"          -> "41 00 BE 3F A8 13"
            lastCmd == "010C"          -> "41 0C 1A F8"          // ~1726 rpm
            lastCmd == "010D"          -> "41 0D 32"             // 50 km/h
            lastCmd == "0105"          -> "41 05 5A"             // 90-40 = 50 °C
            lastCmd == "012F"          -> "41 2F 7F"             // ~49,8% brandstof
            lastCmd == "0142"          -> "41 42 33 9C"         // ~13,2 V
            lastCmd == "03"            -> "43 02 01 33 41 01"    // 2 DTC's: P0133, C0101
            lastCmd == "07"            -> "47 01 19 00"          // pending P1900
            lastCmd == "0A"            -> "4A 00"                // geen permanente
            lastCmd == "04"            -> "44"                   // gewist
            lastCmd.startsWith("0902") -> "49 02 01 00 00 00 00" // VIN-stub
            lastCmd.startsWith("22")   -> "62 ${lastCmd.drop(2)} 12 34" // UDS readDataByIdentifier stub
            lastCmd.startsWith("19")   -> "59 02 FF E3 00 55 13" // UDS readDTCInformation stub
            lastCmd.startsWith("10")   -> "50 ${lastCmd.drop(2).take(2)} 00 32 01 F4" // session control
            else                       -> "NO DATA"
        }
    }

    private fun demoEv(cmd: String): String {
        fun hex(b: List<Int>) = b.joinToString(" ") { "%02X".format(it) }
        fun u16(v: Int) = listOf(v shr 8, v and 255)
        if (cmd in listOf("22F187", "22F188", "22F18C")) return "62 ${cmd.drop(2).chunked(2).joinToString(" ")} " + hex("DEMO-ECU".map { it.code })
        if (cmd == "22F1A0") return "62 F1 A0 01"
        if (cmd in listOf("22F18A", "22F194", "22F195")) return "62 ${cmd.drop(2).chunked(2).joinToString(" ")} 44 45 4D 4F"
        if (cmd.startsWith("10")) return "50 ${cmd.drop(2)}"
        if (cmd.startsWith("1902")) return "59 02 FF"
        if (cmd == "17FF00") return "57 00"
        val data = when (cmd) {
            "222002" -> u16(3750)
            "223206" -> listOf(94)
            "223203" -> u16(760)
            "223204" -> u16(32788)
            "222006" -> listOf(1, 0x86, 0xA0)
            "229002" -> u16(7500)
            "229003" -> u16(9400)
            "229005" -> u16(3800)
            "22925D" -> u16(32480)
            "229012" -> u16(1040)
            "2291C8" -> listOf(0, 0x98, 0x58)
            "2101" -> MutableList(if (vehicle == EvVehicle.LEAF_40_62) 51 else 39) { 0 }.also { d ->
                val off = if (vehicle == EvVehicle.LEAF_40_62) 2 else 0
                d[26 + off] = 0x26; d[27 + off] = 0x9A
                d[33 + off] = 0x0B; d[34 + off] = 0x32; d[35 + off] = 0x90
                if (off == 2) { d[31] = 0x0B; d[32] = 0x71; d[33] = 0xB0 }
            }
            "2104" -> if (vehicle == EvVehicle.ZOE_PH1) List(36) { if (it % 3 == 2) 65 else 0 }
                else MutableList(if (vehicle == EvVehicle.LEAF_40_62) 29 else 14) { 0 }.also { d ->
                    for (i in 0 until 4) { d[i * 3] = 1; d[i * 3 + 1] = 0xD2; d[i * 3 + 2] = 25 }
                }
            "2102" -> (0 until 96).flatMap { u16(3900 + it % 9) } + u16(37500) + u16(37500)
            "2141" -> (0 until 62).flatMap { u16(3900 + it % 9) }
            "2142" -> (0 until 34).flatMap { u16(3900 + it % 9) }
            "2161" -> MutableList(329) { 0 }.also { it[2] = 0x24; it[3] = 0xB8 }
            else -> null
        }
        if (data != null) return hex(listOf(cmd.take(2).toInt(16) + 0x40) + cmd.drop(2).chunked(2).map { it.toInt(16) } + data)
        if (vehicle == EvVehicle.ZOE_PH2 && cmd.startsWith("22") && cmd.drop(2).toIntOrNull(16) in EvRequests.zoe50CellDids)
            return "62 ${cmd.drop(2).chunked(2).joinToString(" ")} 0F A0"
        return when (cmd) {
            "1902FF" -> "59 02 FF" // synthetic clean result, always marked DEMO by UI/report
            "03" -> "43 00"
            "07" -> "47 00"
            "0A" -> "4A 00"
            else -> "NO DATA"
        }
    }
}
