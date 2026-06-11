package be.technop.openrlink.obd

/**
 * Decodeert Diagnostic Trouble Codes uit een OBD-respons.
 * 2 bytes per DTC. De bovenste 2 bits van byte1 bepalen het systeem:
 *   00=P (Powertrain) 01=C (Chassis) 10=B (Body) 11=U (Network).
 * De rest is BCD-achtig genibbeld tot Pxxxx.
 */
object DtcDecoder {

    private val systemLetters = charArrayOf('P', 'C', 'B', 'U')

    /** @param hexResponse bv "43 02 01 33 41 01" (mode 03, 2 codes). */
    fun decode(hexResponse: String): List<String> {
        val bytes = hexResponse
            .replace("\n", " ")
            .split(" ", limit = 0)
            .mapNotNull { it.trim().takeIf { t -> t.length == 2 }?.toIntOrNull(16) }
        if (bytes.isEmpty()) return emptyList()

        // Eerste byte is de mode-respons (0x43/0x47/0x4A) en wordt overgeslagen.
        // Daarna kan een count-byte volgen: als de rest een oneven aantal bytes is,
        // sla die count-byte ook over zodat enkel complete 2-byte DTC's resten.
        var payload = bytes.drop(1)
        if (payload.size % 2 == 1) payload = payload.drop(1)
        val out = mutableListOf<String>()
        var i = 0
        while (i + 1 < payload.size) {
            val b1 = payload[i]; val b2 = payload[i + 1]
            i += 2
            if (b1 == 0 && b2 == 0) continue // 00 00 = lege slot
            val letter = systemLetters[(b1 shr 6) and 0x03]
            val d1 = (b1 shr 4) and 0x03
            val d2 = b1 and 0x0F
            val d3 = (b2 shr 4) and 0x0F
            val d4 = b2 and 0x0F
            out.add("%c%d%X%X%X".format(letter, d1, d2, d3, d4))
        }
        return out
    }
}
