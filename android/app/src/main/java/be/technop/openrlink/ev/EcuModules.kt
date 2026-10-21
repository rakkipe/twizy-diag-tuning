package be.technop.openrlink.ev

data class EcuVariant(val file: String, val label: String, val identities: List<EcuIdentity>)
data class EcuIdentity(val diagnostic: String, val supplier: String, val software: String, val version: String) {
    val complete get() = listOf(diagnostic, supplier, software, version).all { it.isNotBlank() }
}
data class EcuModule(
    val target: EcuTarget,
    val mask: Int = 0xFF,
    val session: Int? = null,
    val sessionRequired: Boolean = false,
    val source: String = "Gepubliceerd moduleadres",
    val variants: List<EcuVariant> = emptyList(),
    val readMasks: List<Int> = emptyList()
) {
    init { require(session == null || session in listOf(3, 0xC0)) { "Alleen leessessies toegestaan" } }
    val id get() = "${target.tx}/${target.rx}"
}

/** Published physical addresses only. No guessed RX IDs, virtual ECUs or broadcast probes. */
object EcuModules {
    fun validPair(tx: String, rx: String): Boolean = tx.length == rx.length && tx != rx && listOf(tx, rx).all {
        (it.matches(Regex("[0-9A-F]{3}")) && it.toLong(16) in 1..0x7FF && it != "7DF") ||
            (it.matches(Regex("[0-9A-F]{8}")) && it.toLong(16) in 1..0x1FFFFFFF)
    }
    fun builtIn(v: EvVehicle): List<EcuModule> {
        fun m(name: String, tx: String, rx: String, mask: Int = 255, session: Int? = if (v == EvVehicle.ZOE_PH1) 0xC0 else if (v == EvVehicle.ZOE_PH2) 3 else null, required: Boolean = false) =
            EcuModule(EcuTarget(name, tx, rx), mask, session, required)
        val commonZoe = listOf(
            m("Airbag / SRS", "752", "772"), m("UCH / BCM", "745", "765"),
            m("Klimaatregeling", "744", "764"), m("Stuurbekrachtiging", "742", "762"),
            m("Instrumentenpaneel", "743", "763"), m("USM / UPC", "74D", "76D"),
            m("Parkeersensoren", "74E", "76E"), m("DCM / telematica", "7CA", "7DA")
        )
        return when (v) {
            EvVehicle.ZOE_PH1 -> commonZoe + listOf(
                m("EVC / voertuigcontroller", "7E4", "7EC", 0xAF), m("LBC / batterij", "79B", "7BB", 0x0F),
                m("LBC2 / batterij", "796", "7B6", 0x0F), m("PEB / omvormer", "75A", "77E"),
                m("ABS / ESC", "740", "760"), m("UBP / rempedaal", "79C", "7BC"),
                m("BCB / lader", "792", "793", 0xFF, 0xC0, true)
            )
            EvVehicle.ZOE_PH2 -> commonZoe.map { if (it.target.tx == "74D") it.copy(mask = 0xAF, session = 0xC0) else it } + listOf(
                m("LBC / batterij", "18DADBF1", "18DAF1DB"), m("LBC2 / batterij", "18DADCF1", "18DAF1DC"),
                m("EVC / voertuigcontroller", "18DADAF1", "18DAF1DA", 0xAF),
                m("Omvormer", "18DADFF1", "18DAF1DF", 0xAF, 0xC0), m("BCB / lader", "18DADEF1", "18DAF1DE", 0xAF, 0xC0),
                m("ABS / ESC", "18DA2DF1", "18DAF12D", 0x01), m("E-ACT / rembekrachtiging", "18DAE2F1", "18DAF1E2", 0x01),
                m("Security Gateway", "18DAD2F1", "18DAF1D2"), m("Audioversterker", "782", "7A2"),
                m("Elektrische parkeerrem", "755", "775", 0xAF), m("Frontcamera", "7DC", "7DD"),
                m("Handsfree module", "799", "7B9"), m("IDM / voertuigdynamiek", "70C", "700"),
                m("Adaptieve cruisecontrol radar", "723", "735"), m("Multimedia-interface", "714", "734"),
                m("Navigatie / AV", "747", "767"), m("PLC / laadcommunicatie", "7D8", "7C8"),
                m("Voetgangersgeluid", "73F", "761", session = 0xC0), m("Draadloze telefoonlader", "7AA", "7AD")
            )
            EvVehicle.LEAF_24_30, EvVehicle.LEAF_40_62 -> listOf(
                m("VCM / voertuigcontroller", "797", "79A"), m("LBC / batterij (EV-CAN)", "79B", "7BB"),
                m("BCM / carrosserie", "745", "765"), m("ABS / VDC", "740", "760"),
                m("Omvormer / motorcontroller", "784", "78C"), m("Instrumentenpaneel", "743", "763"),
                m("Klimaatregeling", "744", "764"), m("Elektronische rem", "70E", "70F"),
                m("VSP / voetgangersgeluid", "73F", "761"), m("Stuurbekrachtiging", "742", "762"),
                m("TCU / telematica", "746", "783"), m("Multimedia / navigatie", "747", "767"),
                m("IPDM / stroomverdeling", "74D", "76D"), m("Airbag / SRS", "752", "772"),
                m("Lader", "792", "793")
            ) + if (v == EvVehicle.LEAF_24_30) listOf(m("SHIFT (alleen vroege ZE0)", "79D", "7BD")) else emptyList()
            else -> emptyList()
        }.sortedBy { it.target.name }
    }
    fun merge(v: EvVehicle, database: List<EcuModule>): List<EcuModule> {
        val merged = builtIn(v).associateBy { it.id }.toMutableMap()
        for (module in database) {
            val previous = merged[module.id]
            merged[module.id] = if (previous == null) module else previous.copy(
                variants = module.variants, readMasks = module.readMasks, source = "Gepubliceerd adres + eigen ecu.zip")
        }
        return merged.values.sortedBy { it.target.name }
    }
}
