package be.technop.openrlink.ev

data class EcuTarget(val name: String, val tx: String, val rx: String)

enum class EvVehicle(val label: String, val note: String) {
    TWIZY("Renault Twizy", "Bestaande Twizy-diagnose en SEVCON-profielen."),
    ZOE_PH1("Renault Zoe — 22 / 41 kWh (fase 1)", "Fase 1 (2012–2019), 11-bit CAN. Q210/R240/Q90/R90/R110. Beschikbaarheid verschilt per ECU-versie."),
    ZOE_PH2("Renault Zoe — ZE50 (fase 2)", "Fase 2, 29-bit CAN via OBD. SOC, SOH, stroom, spanning en 96 celspanningen. Contact aan."),
    LEAF_24_30("Nissan Leaf — 24 / 30 kWh", "ZE0/AZE0. Batterij-uitlezing vereist bereikbare EV-CAN. SOH wordt hier niet uit een aangenomen nieuwcapaciteit berekend."),
    LEAF_40_62("Nissan Leaf — 40 / 62 kWh", "ZE1. Contact aan; de gateway kan diagnose blokkeren. Geen antwoord betekent niet dat de batterij foutloos is.");

    val isTwizy get() = this == TWIZY
    val isLeaf get() = this == LEAF_24_30 || this == LEAF_40_62
    val battery: EcuTarget get() = if (this == ZOE_PH2) EcuTarget("LBC / batterij", "18DADBF1", "18DAF1DB")
        else EcuTarget("LBC / batterij", "79B", "7BB")
    val controller: EcuTarget get() = if (this == ZOE_PH2) EcuTarget("EVC / voertuigcontroller", "18DADAF1", "18DAF1DA")
        else EcuTarget("EVC / voertuigcontroller", "7E4", "7EC")
    val scanTargets: List<EcuTarget> get() = EcuModules.builtIn(this).map { it.target }
}

/** All EV requests are reads; these profiles can never execute tuning. */
data class EvRead(val target: EcuTarget, val command: String) {
    init { require(command.matches(Regex("21[0-9A-F]{2}|22[0-9A-F]{4}"))) }
    val responsePrefix: List<Int> get() = listOf(command.take(2).toInt(16) + 0x40) +
        command.drop(2).chunked(2).map { it.toInt(16) }
}

object EvRequests {
    // The ZE50 skips DID 9040, 9060 and 9080 in the 96-cell sequence (OVMS).
    val zoe50CellDids = ((0x9021..0x903F) + (0x9041..0x905F) + (0x9061..0x907F) + (0x9081..0x9083)).toList()
    fun summary(v: EvVehicle): List<EvRead> = when (v) {
        EvVehicle.TWIZY -> emptyList()
        EvVehicle.ZOE_PH1 -> listOf("222002", "223206", "223203", "223204", "222006").map { EvRead(v.controller, it) } + EvRead(v.battery, "2104")
        EvVehicle.ZOE_PH2 -> listOf("229002", "229003", "229005", "22925D", "229012", "2291C8").map { EvRead(v.battery, it) }
        else -> listOf("2101", "2104").map { EvRead(v.battery, it) } +
            if (v == EvVehicle.LEAF_40_62) listOf(EvRead(v.battery, "2161")) else emptyList()
    }
    fun cells(v: EvVehicle): List<EvRead> = when (v) {
        EvVehicle.ZOE_PH1 -> listOf("2141", "2142").map { EvRead(v.battery, it) }
        EvVehicle.ZOE_PH2 -> zoe50CellDids.map { EvRead(v.battery, "22%04X".format(it)) }
        EvVehicle.LEAF_24_30, EvVehicle.LEAF_40_62 -> listOf(EvRead(v.battery, "2102"))
        else -> emptyList()
    }
}
