package be.technop.openrlink.elm

import be.technop.openrlink.transport.Transport

/**
 * Stuurt het ELM327/STN-protocol aan (vLinker FS2 = STN-chip → ondersteunt zowel
 * AT- als ST-commando's). Eén centrale plek voor:
 *   - init-handshake
 *   - protocolkeuze (CAN 11/29-bit, 500/250 kbps)
 *   - header-/filterconfiguratie voor gerichte ECU-diagnose (UDS)
 *
 * Alle publieke methodes blokkeren en horen op een IO-dispatcher te draaien.
 */
class Elm327(private val transport: Transport) {

    /** Laatste ruwe regels die de adapter teruggaf — voor de terminal/log. */
    var lastRaw: String = ""
        private set

    /** Laag commando: stuurt "<cmd>\r" en leest tot prompt. Strip echo/whitespace. */
    fun command(cmd: String, timeoutMs: Long = 4000): String {
        transport.write((cmd + "\r").toByteArray())
        val resp = transport.readUntilPrompt(timeoutMs)
        lastRaw = resp
        // ELM kan het commando echoën (als ATE niet 0 staat) — verwijder echo-regel.
        return resp.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(cmd, ignoreCase = true) }
            .joinToString("\n")
            .trim()
    }

    /**
     * Standaard init voor generieke OBD-II (11-bit CAN @500k).
     * ATE0 echo uit, ATL0 linefeeds uit, ATS0 spaties uit (sneller parsen),
     * ATH1 headers aan (nodig om ECU-bron te zien bij multi-ECU), ATSP6 = ISO15765 11/500.
     */
    fun initObd(): Boolean {
        command("ATZ", 6000)            // reset
        command("ATE0")                 // echo off
        command("ATL0")                 // linefeeds off
        command("ATS0")                 // spaces off
        command("ATH1")                 // headers on
        command("ATSP6")                // CAN 11-bit 500k
        val v = command("ATI")          // identify
        return v.contains("ELM", ignoreCase = true) || v.contains("STN", ignoreCase = true) || v.contains("v")
    }

    /**
     * Config voor gerichte UDS-diagnose op één ECU.
     * @param txHeader  request-arbitration-ID (bv. "75A" voor Twizy PEB) — 11-bit hex.
     * @param rxHeader  verwacht respons-ID (txHeader + 8 conform ISO-TP conventie, bv. "762").
     *
     * ATCAF1 = CAN Auto Formatting AAN → ELM doet ISO-TP (multi-frame + flow control) zelf.
     * ATFCSM1 + ATFCSH/ATFCSD = expliciete flow-control voor adapters die het niet automatisch doen.
     */
    fun configUdsTarget(txHeader: String, rxHeader: String) {
        require(txHeader.matches(Regex("[0-9A-Fa-f]{3}|[0-9A-Fa-f]{8}")) && txHeader.length == rxHeader.length)
        require(rxHeader.matches(Regex("[0-9A-Fa-f]{3}|[0-9A-Fa-f]{8}")))
        fun set(cmd: String) { check(command(cmd).trim() == "OK") { "Adapter ondersteunt $cmd niet" } }
        set(if (txHeader.length == 8) "ATSP7" else "ATSP6")
        set("ATH1"); set("ATS1"); set("ATCAF1"); set("ATAL"); set("ATCFC1")
        set("ATSH$txHeader")
        set("ATCRA$rxHeader")
        set("ATFCSH$txHeader")
        set("ATFCSD300000")
        set("ATFCSM1")
    }

    /** Restore every setting changed by CANopen/targeted reads, including broadcast header. */
    fun configGenericObd() {
        command("ATSP6"); command("ATCAF1"); command("ATH1"); command("ATS1")
        command("ATFCSM0"); command("ATCFC1"); command("ATSH7DF")
        clearFilter()
    }

    /** Reset filter zodat alle ECU's weer antwoorden (na gerichte UDS). */
    fun clearFilter() {
        command("ATCRA")
        command("ATAR")                 // auto receive address
    }

    /** Stuurt een ruwe hex-PDU (zonder spaties of mét, beide ok) en geeft hex-respons. */
    fun sendHex(hex: String, timeoutMs: Long = 5000): String =
        command(hex.replace(" ", "").uppercase(), timeoutMs)
}
