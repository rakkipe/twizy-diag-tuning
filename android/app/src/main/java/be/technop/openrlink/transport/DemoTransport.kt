package be.technop.openrlink.transport

/**
 * Simuleert een ELM327 zonder hardware. Handig om de UI/flow te testen
 * (vgl. AndrOBD demo-mode). Geeft plausibele antwoorden op de belangrijkste commando's.
 */
class DemoTransport : Transport {
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
            lastCmd == "07"            -> "47 01 90 00"          // pending P1900
            lastCmd == "0A"            -> "4A 00"                // geen permanente
            lastCmd == "04"            -> "44"                   // gewist
            lastCmd.startsWith("0902") -> "49 02 01 00 00 00 00" // VIN-stub
            lastCmd.startsWith("22")   -> "62 ${lastCmd.drop(2)} 12 34" // UDS readDataByIdentifier stub
            lastCmd.startsWith("19")   -> "59 02 FF E3 00 55 13" // UDS readDTCInformation stub
            lastCmd.startsWith("10")   -> "50 ${lastCmd.drop(2).take(2)} 00 32 01 F4" // session control
            else                       -> "NO DATA"
        }
    }
}
