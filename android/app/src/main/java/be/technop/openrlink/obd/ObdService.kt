package be.technop.openrlink.obd

import be.technop.openrlink.elm.Elm327

/** High-level generieke OBD-II operaties (mode 01/03/04/07/0A). */
class ObdService(private val elm: Elm327) {

    fun readStoredDtcs(): List<String> = DtcDecoder.decode(elm.command("03"))
    fun readPendingDtcs(): List<String> = DtcDecoder.decode(elm.command("07"))
    fun readPermanentDtcs(): List<String> = DtcDecoder.decode(elm.command("0A"))

    /** Mode 04: wis DTC's + MIL. Geeft true bij "44" bevestiging. */
    fun clearDtcs(): Boolean = elm.command("04").replace(" ", "").contains("44")

    data class LiveValue(val label: String, val value: Double, val unit: String)

    fun readPid(pid: Pid): LiveValue? {
        val raw = elm.command(pid.command, 2500)
        val payload = Pids.parsePayload(pid, raw) ?: return null
        return try {
            LiveValue(pid.label, pid.decode(payload), pid.unit)
        } catch (e: Exception) { null }
    }

    /** Snapshot van alle gedefinieerde PID's (skipt de niet-ondersteunde). */
    fun snapshot(): List<LiveValue> = Pids.LIST.mapNotNull { readPid(it) }
}
