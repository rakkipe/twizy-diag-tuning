package be.technop.openrlink.sevcon

import be.technop.openrlink.elm.Elm327

/**
 * SEVCON Gen4 via CANopen SDO over ELM327 (ATCAF0 = ruwe CANopen payload).
 * Node 1: request COB-ID 0x601, response 0x581. Getrouwe port van de bewezen
 * PC-tool: expedited write = command 0x22, read = 0x40, login 0x4BDF, NMT via
 * COB-ID 0x000. Alle methodes blokkeren → op IO-dispatcher draaien.
 */
class SevconCanopen(private val elm: Elm327) {

    data class R(val ok: Boolean, val value: Long, val status: String)

    private fun rd(idx: Int, sub: Int): String =
        "40%02X%02X%02X00000000".format(idx and 0xFF, (idx shr 8) and 0xFF, sub)

    private fun wr(idx: Int, sub: Int, v: Long): String =
        "22%02X%02X%02X%02X%02X%02X%02X".format(
            idx and 0xFF, (idx shr 8) and 0xFF, sub,
            (v and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(),
            ((v shr 16) and 0xFF).toInt(), ((v shr 24) and 0xFF).toInt()
        )

    private fun parse(raw: String): IntArray? {
        val s = raw.filter { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }.uppercase()
        val i = s.indexOf("581")
        if (i < 0) return null
        val body = s.substring(i + 3)
        if (body.length < 16) return null
        return IntArray(8) { body.substring(it * 2, it * 2 + 2).toInt(16) }
    }

    /** Zet de adapter in ruwe CANopen-modus, gericht op node 1. */
    fun configCanopen() {
        elm.command("ATSP6"); elm.command("ATCAF0"); elm.command("ATH1")
        elm.command("ATSH601"); elm.command("ATCRA581"); elm.command("ATAT1")
    }

    fun read(idx: Int, sub: Int): R {
        val raw = elm.command(rd(idx, sub))
        if (raw.contains("NO DATA")) return R(false, 0, "NO_DATA")
        val b = parse(raw) ?: return R(false, 0, "UNPARSED:$raw")
        if (b[0] == 0x80) {
            val v = (b[4].toLong()) or (b[5].toLong() shl 8) or (b[6].toLong() shl 16) or (b[7].toLong() shl 24)
            return R(false, v, "ABORT:%08X".format(v))
        }
        val v = (b[4].toLong()) or (b[5].toLong() shl 8) or (b[6].toLong() shl 16) or (b[7].toLong() shl 24)
        return R(true, v, "OK")
    }

    fun write(idx: Int, sub: Int, v: Long): R {
        val raw = elm.command(wr(idx, sub, v))
        if (raw.contains("NO DATA")) return R(false, 0, "NO_DATA")
        val b = parse(raw) ?: return R(false, 0, "UNPARSED:$raw")
        if (b[0] == 0x80) {
            val e = (b[4].toLong()) or (b[5].toLong() shl 8) or (b[6].toLong() shl 16) or (b[7].toLong() shl 24)
            return R(false, e, "ABORT:%08X".format(e))
        }
        if (b[0] == 0x60 && b[1] == (idx and 0xFF) && b[2] == ((idx shr 8) and 0xFF) && b[3] == sub)
            return R(true, 0, "OK")
        return R(false, 0, "UNEXPECTED_ACK")
    }

    fun login(): Pair<Boolean, String> {
        val r0 = read(0x5000, 0x01)
        if (r0.ok && r0.value == 4L) return true to "AL_INGELOGD"
        val w1 = write(0x5000, 0x03, 0)
        if (!w1.ok) return false to "RESET_FOUT:${w1.status}"
        val w2 = write(0x5000, 0x02, 0x4BDF)
        if (!w2.ok) return false to "PWD_FOUT:${w2.status}"
        val r1 = read(0x5000, 0x01)
        return (r1.ok && r1.value == 4L) to if (r1.ok && r1.value == 4L) "LOGIN_OK" else "VERIFY_FOUT:${r1.value}"
    }

    private fun nmt(cmd: Int) {
        elm.command("ATSH000"); elm.command("ATCRA700")
        elm.command("%02X01".format(cmd))
        elm.command("ATSH601"); elm.command("ATCRA581")
        Thread.sleep(100)
    }

    fun nmtStart() = nmt(0x01)          // operationeel (nodig vóór login/writes)
    fun enterCfg() = nmt(0x80)          // pre-operationeel (voor speed/power)
    fun leaveCfg() = nmt(0x01)          // terug operationeel
}
