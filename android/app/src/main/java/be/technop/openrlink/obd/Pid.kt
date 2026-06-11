package be.technop.openrlink.obd

/**
 * Standaard OBD-II mode-01 PID's met formule en eenheid.
 * Uitbreiden = één regel toevoegen aan de lijst (modulair).
 */
data class Pid(
    val mode: String,        // "01"
    val pid: String,         // "0C"
    val label: String,
    val unit: String,
    val decode: (IntArray) -> Double
) {
    val command get() = mode + pid
}

object Pids {
    /** data = de payload-bytes ná "41 <pid>". */
    val LIST: List<Pid> = listOf(
        Pid("01", "0C", "Toerental", "rpm")    { d -> (d[0] * 256 + d[1]) / 4.0 },
        Pid("01", "0D", "Snelheid", "km/h")    { d -> d[0].toDouble() },
        Pid("01", "05", "Koelvloeistof", "°C") { d -> (d[0] - 40).toDouble() },
        Pid("01", "0F", "Inlaatlucht", "°C")   { d -> (d[0] - 40).toDouble() },
        Pid("01", "11", "Gasklep", "%")        { d -> d[0] * 100.0 / 255.0 },
        Pid("01", "2F", "Brandstof", "%")      { d -> d[0] * 100.0 / 255.0 },
        Pid("01", "42", "Module-spanning", "V"){ d -> (d[0] * 256 + d[1]) / 1000.0 },
        Pid("01", "04", "Motorbelasting", "%") { d -> d[0] * 100.0 / 255.0 },
        Pid("01", "0B", "MAP", "kPa")          { d -> d[0].toDouble() },
        Pid("01", "10", "MAF", "g/s")          { d -> (d[0] * 256 + d[1]) / 100.0 },
    )

    /**
     * Parse "41 0C 1A F8" → payload [0x1A, 0xF8].
     * Geeft null als het geen geldige respons op deze PID is.
     */
    fun parsePayload(pid: Pid, raw: String): IntArray? {
        val bytes = raw.split(" ", "\n")
            .mapNotNull { it.trim().takeIf { t -> t.length == 2 }?.toIntOrNull(16) }
        // verwacht: 0x41, <pidbyte>, data...
        val expectMode = 0x40 + pid.mode.toInt(16)
        val pidByte = pid.pid.toInt(16)
        val idx = bytes.indexOfFirst { it == expectMode }
        if (idx < 0 || idx + 1 >= bytes.size || bytes[idx + 1] != pidByte) return null
        return bytes.drop(idx + 2).toIntArray()
    }
}
