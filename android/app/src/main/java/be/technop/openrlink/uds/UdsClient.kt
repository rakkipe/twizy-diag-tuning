package be.technop.openrlink.uds

import be.technop.openrlink.elm.Elm327

/**
 * UDS (ISO 14229) over ISO-TP. Omdat Elm327.configUdsTarget() ATCAF1 zet,
 * doet de adapter de ISO-TP framing/flow-control zelf — wij sturen enkel de
 * kale service-PDU als hex en krijgen de geassembleerde respons terug.
 *
 * Workflow per ECU:
 *   elm.configUdsTarget("75A", "762")   // bv. Twizy PEB
 *   uds.diagnosticSession(0x03)
 *   uds.readDataByIdentifier(0xF190)
 */
class UdsClient(private val elm: Elm327) {

    data class Result(val ok: Boolean, val data: List<Int>, val raw: String, val nrc: String? = null)

    private fun send(pdu: String): Result {
        val raw = elm.sendHex(pdu)
        val bytes = raw.split(" ", "\n")
            .mapNotNull { it.trim().takeIf { t -> t.length == 2 }?.toIntOrNull(16) }
        if (bytes.isEmpty() || raw.contains("NO DATA") || raw.contains("ERROR"))
            return Result(false, emptyList(), raw, nrc = "Geen/ongeldige respons")
        // Negatieve respons: 7F <sid> <nrc>
        if (bytes[0] == 0x7F && bytes.size >= 3)
            return Result(false, bytes, raw, nrc = nrcText(bytes[2]))
        return Result(true, bytes, raw)
    }

    /** 0x10 DiagnosticSessionControl. 0x01=default 0x02=programming 0x03=extended. */
    fun diagnosticSession(sub: Int): Result = send("10%02X".format(sub))

    /** 0x22 ReadDataByIdentifier (DID 2 bytes). */
    fun readDataByIdentifier(did: Int): Result = send("22%04X".format(did))

    /** 0x2E WriteDataByIdentifier. */
    fun writeDataByIdentifier(did: Int, dataHex: String): Result =
        send("2E%04X".format(did) + dataHex.replace(" ", ""))

    /** 0x19 ReadDTCInformation. sub 0x02 = byMask, statusMask bv 0xFF. */
    fun readDtcInformation(sub: Int, mask: Int = 0xFF): Result = send("19%02X%02X".format(sub, mask))

    /** 0x14 ClearDiagnosticInformation. group 0xFFFFFF = alles. */
    fun clearDiagnosticInfo(group: Int = 0xFFFFFF): Result = send("14%06X".format(group))

    /** 0x31 RoutineControl. type 0x01=start 0x02=stop 0x03=requestResults. */
    fun routineControl(type: Int, routineId: Int, paramHex: String = ""): Result =
        send("31%02X%04X".format(type, routineId) + paramHex.replace(" ", ""))

    /** 0x3E TesterPresent — houdt de sessie levend. */
    fun testerPresent(): Result = send("3E00")

    companion object {
        fun nrcText(code: Int): String = when (code) {
            0x10 -> "0x10 generalReject"
            0x11 -> "0x11 serviceNotSupported"
            0x12 -> "0x12 subFunctionNotSupported"
            0x13 -> "0x13 incorrectMessageLength"
            0x22 -> "0x22 conditionsNotCorrect"
            0x24 -> "0x24 requestSequenceError"
            0x31 -> "0x31 requestOutOfRange"
            0x33 -> "0x33 securityAccessDenied"
            0x35 -> "0x35 invalidKey"
            0x7E -> "0x7E subFunctionNotSupportedInActiveSession"
            0x7F -> "0x7F serviceNotSupportedInActiveSession"
            0x78 -> "0x78 responsePending (busy)"
            else -> "0x%02X onbekende NRC".format(code)
        }
    }
}
