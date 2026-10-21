package be.technop.openrlink.ev

data class EvMetric(val label: String, val value: Double, val unit: String)
data class EvDecoded(val metrics: List<EvMetric> = emptyList(), val cells: Map<Int, Double> = emptyMap())

/** Offsets refer to the data AFTER 61 xx / 62 xxxx. Reject unknown layouts. */
object EvDecoder {
    fun decode(v: EvVehicle, command: String, d: List<Int>): EvDecoded {
        val metrics = mutableListOf<EvMetric>()
        val cells = linkedMapOf<Int, Double>()
        fun u16(i: Int) = d[i] * 256 + d[i + 1]
        fun u24(i: Int) = d[i] * 65536 + d[i + 1] * 256 + d[i + 2]
        fun add(label: String, value: Double, unit: String, range: ClosedFloatingPointRange<Double>) {
            if (value.isFinite() && value in range) metrics.add(EvMetric(label, value, unit))
        }
        fun cell(i: Int, voltage: Double) { if (voltage in 2.0..4.5) cells[i] = voltage }
        when (v) {
            EvVehicle.ZOE_PH1 -> when (command) {
                "222002" -> if (d.size == 2) add("SOC", u16(0) * 0.02, "%", 0.0..100.0)
                "223206" -> if (d.size == 1) add("SOH", d[0].toDouble(), "%", 0.0..100.0)
                "223203" -> if (d.size == 2) add("Accuspanning", u16(0) * 0.5, "V", 100.0..500.0)
                "223204" -> if (d.size == 2) add("Accustroom", (u16(0) - 32768) * 0.25, "A", -1000.0..1000.0)
                "222006" -> if (d.size == 3) add("Kilometerstand", u24(0).toDouble(), "km", 0.0..3000000.0)
                "2104" -> if (d.size >= 36) for (i in 0 until 12) {
                    if (d[i * 3 + 2] != 255) add("Accutemperatuur ${i + 1}", d[i * 3 + 2] - 40.0, "°C", -40.0..100.0)
                }
                "2141" -> if (d.size == 124) for (i in 0 until 62) cell(i + 1, u16(i * 2) / 1000.0)
                "2142" -> if (d.size == 68) for (i in 0 until 34) cell(i + 63, u16(i * 2) / 1000.0)
            }
            EvVehicle.ZOE_PH2 -> {
                if (command == "2291C8" && d.size == 3) add("Beschikbare energie", u24(0) * 0.001, "kWh", 0.0..70.0)
                if (d.size == 2) when (command) {
                    "229002" -> add("SOC", u16(0) * 0.01, "%", 0.0..100.0)
                    "229003" -> add("SOH", u16(0) * 0.01, "%", 0.0..100.0)
                    "229005" -> add("Accuspanning", u16(0) * 0.1, "V", 100.0..500.0)
                    "22925D" -> add("Accustroom", 1020 - u16(0) * 0.03125, "A", -1000.0..1000.0)
                    "229012" -> add("Gemiddelde accutemperatuur", u16(0) * 0.0625 - 40, "°C", -40.0..100.0)
                    else -> {
                        val index = EvRequests.zoe50CellDids.indexOf(command.drop(2).toIntOrNull(16))
                        if (index >= 0) cell(index + 1, u16(0) / 1024.0)
                    }
                }
            }
            EvVehicle.LEAF_24_30, EvVehicle.LEAF_40_62 -> when (command) {
                "2101" -> {
                    val ze1 = v == EvVehicle.LEAF_40_62
                    if ((ze1 && d.size == 51) || (!ze1 && d.size in listOf(39, 41))) {
                        val offset = if (ze1) 2 else 0
                        add("Capaciteit", u24(33 + offset) / 10000.0, "Ah", 0.0..250.0)
                        add("Hx (geen SOH)", u16(26 + offset) / (if (ze1) 102.4 else 100.0), "%", 0.0..200.0)
                        if (ze1) add("SOC", u24(31) / 10000.0, "%", 0.0..100.0)
                    }
                }
                "2102" -> if (d.size == 196) {
                    for (i in 0 until 96) cell(i + 1, u16(i * 2) / 1000.0)
                    add("Accuspanning", u16(192) / 100.0, "V", 100.0..500.0)
                }
                "2104" -> if ((v == EvVehicle.LEAF_24_30 && d.size == 14) || (v == EvVehicle.LEAF_40_62 && d.size == 29)) {
                    for (i in 0 until 4) if (u16(i * 3) != 65535)
                        add("Accutemperatuur ${i + 1} (benaderd)", -0.102 * (u16(i * 3) - 710), "°C", -40.0..100.0)
                }
                "2161" -> if (v == EvVehicle.LEAF_40_62 && d.size == 329) add("SOH", u16(2) / 100.0, "%", 0.0..100.0)
            }
            else -> Unit
        }
        return EvDecoded(metrics, cells)
    }
}
