package be.technop.openrlink.renault

/**
 * ECU-adressering voor Renault-diagnose over CAN (11-bit, ISO-TP).
 *
 * !! LET OP !!  Diagnostische request/response-ID's verschillen per platform en
 * moeten geverifieerd worden tegen DDT4All-ECU-XML of een live CAN-sniff (je
 * M5StickC + CAN-Unit). De waarden hieronder zijn STARTPUNTEN, geen garantie.
 * Pas ze aan in deze ene tabel; de rest van de app volgt automatisch.
 *
 * Conventie 11-bit fysiek adresseren: respons-ID = request-ID + 8 (vaak),
 * maar Renault wijkt hier soms van af → daarom expliciet beide velden.
 */
data class RenaultEcu(
    val key: String,
    val label: String,
    val txHeader: String,   // request arbitration ID (hex, 3 nibbles)
    val rxHeader: String,   // response arbitration ID (hex, 3 nibbles)
    val verified: Boolean,  // false = nog te bevestigen
    val note: String = ""
)

object RenaultEcus {
    /** Twizy-relevante ECU's. txHeader 75A = PEB/inverter zoals besproken (te bevestigen). */
    val TWIZY: List<RenaultEcu> = listOf(
        RenaultEcu("LBC_BMS", "LBC / BMS (batterij)",    "79B", "7BB", verified = true,
            note = "Live bevestigd: antwoordt op UDS 19 02 (leest DTC's)."),
        RenaultEcu("BCB",     "BCB / Charger",           "792", "793", verified = true,
            note = "Live bevestigd: aanwezig; DTC-dienst afhankelijk van firmware."),
        RenaultEcu("TDB",     "TDB / Cluster (dashboard)","743", "763", verified = true,
            note = "Live bevestigd: aanwezig (data via service 21)."),
        RenaultEcu("PEB",     "PEB / Sevcon (CANopen)",  "75A", "762", verified = false,
            note = "Sevcon praat CANopen (node 1), niet UDS — antwoordt hier niet."),
    )

    fun byKey(list: List<RenaultEcu>, key: String): RenaultEcu? = list.firstOrNull { it.key == key }
}
