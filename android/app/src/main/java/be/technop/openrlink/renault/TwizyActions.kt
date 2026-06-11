package be.technop.openrlink.renault

import be.technop.openrlink.elm.Elm327
import be.technop.openrlink.uds.UdsClient

/**
 * Vooraf gedefinieerde diagnose-acties voor de Twizy. Elke actie is een
 * (ECU + UDS-request)-combinatie. Modulair: nieuwe actie = item toevoegen.
 *
 * De DID's hieronder zijn generieke UDS-identifiers (F18x/F19x = ISO-gestandaardiseerd)
 * plus placeholders voor Sevcon-specifieke data die je via DDT4All/sniff invult.
 */
data class TwizyAction(
    val title: String,
    val ecuKey: String,
    val run: (UdsClient) -> UdsClient.Result
)

class TwizyActions(private val elm: Elm327) {

    private val uds = UdsClient(elm)

    val actions: List<TwizyAction> = listOf(
        TwizyAction("PEB: lees actieve DTC's (UDS 0x19)", "PEB") { it.readDtcInformation(0x02, 0xFF) },
        TwizyAction("PEB: bootsoftware-ID (DID F180)",     "PEB") { it.readDataByIdentifier(0xF180) },
        TwizyAction("PEB: ECU serienummer (DID F18C)",     "PEB") { it.readDataByIdentifier(0xF18C) },
        TwizyAction("LBC: batterij-DTC's (UDS 0x19)",      "LBC_BMS") { it.readDtcInformation(0x02, 0xFF) },
        TwizyAction("LBC: SOC/spanning (DID 2002)*",       "LBC_BMS") { it.readDataByIdentifier(0x2002) },
        TwizyAction("BCB: charger-DTC's (UDS 0x19)",       "BCB") { it.readDtcInformation(0x02, 0xFF) },
        TwizyAction("TDB: cluster-ID (DID F190 VIN)",      "TDB") { it.readDataByIdentifier(0xF190) },
    )

    /**
     * Voert een actie uit: zet eerst de header naar de juiste ECU, opent extended
     * sessie, dan de eigenlijke request. Geeft het UDS-resultaat terug.
     */
    fun execute(action: TwizyAction): UdsClient.Result {
        val ecu = RenaultEcus.byKey(RenaultEcus.TWIZY, action.ecuKey)
            ?: return UdsClient.Result(false, emptyList(), "", "Onbekende ECU ${action.ecuKey}")
        elm.configUdsTarget(ecu.txHeader, ecu.rxHeader)
        uds.diagnosticSession(0x03)   // extended diagnostic session
        uds.testerPresent()
        val result = action.run(uds)
        return result
    }
}
