package be.technop.openrlink.sevcon

/** Tuning-profiel in OVMS-eenheden. -1 = fabriekswaarde. */
data class TuningProfile(
    val name: String,
    val speed: Int, val warn: Int,
    val torque: Int, val powerLow: Int, val powerHigh: Int, val current: Int,
    val drive: Int, val neutral: Int, val brake: Int
)

object TuningProfiles {
    // Namen zoals gevraagd: ECO / STAD / SNELWEG / RACE-LITE (+ STOCK om terug te zetten)
    val LIST: List<TuningProfile> = listOf(
        TuningProfile("STOCK",     -1, -1, 100, 100, 100, 100, 100, 18, 18),
        TuningProfile("ECO",       55, 60,  70,  70,  70, 100,  70, 40, 40),
        TuningProfile("STAD",      45, 50,  90,  90,  90, 100,  80, 40, 50),
        TuningProfile("SNELWEG",   90, 95, 110, 120, 120, 110, 100, 18, 25),
        TuningProfile("RACE-LITE", 90, 95, 150, 140, 140, 110, 100, 20, 35),
    )
    fun byName(n: String) = LIST.firstOrNull { it.name == n }
}

/**
 * OVMS/dexterbg-conforme tuning-engine (getrouwe port van de PC-tool).
 * Schrijft alleen na expliciete gebruikersbevestiging; auto stilstaand in N.
 */
class SevconTuner(private val sc: SevconCanopen, private val log: (String) -> Unit) {

    // CFG80 — geverifieerde defaults (Twizy 80 / SEVCON Gen4)
    private val KphMax=80; private val RpmMax=7250; private val RpmRev=900
    private val BrkStart=400; private val BrkEnd=800; private val BrkDown=1250
    private val KphWarn=89; private val RpmWarn=8050; private val WarnOff=550
    private val Trq=55000; private val TrqRated=57000; private val TrqLim=70125; private val MapTrq=0
    private val CurrLim=450000; private val CurrStatorMax=450; private val BoostCurr=540
    private val FMAP=intArrayOf(964,9728,1122,9984); private val EFMAP=intArrayOf(1122,10089,2240,11901)
    private val PwrLo=12182; private val PwrLoLim=17000; private val PwrHi=13000; private val PwrHiLim=17000; private val MaxMotorPwr=4608
    private val Recup=182; private val RecupPrc=18
    private val RampStart=400; private val RampStartPrm=40; private val RampAccel=2500; private val RampAccelPrc=25
    private val PMAP=intArrayOf(880,0,880,2115,659,2700,608,3000,516,3500,421,4500,360,5500,307,6500,273,7250)
    private val FIB=intArrayOf(1,2,3,5,8,13,21)

    private var maxRpm=0; private var maxTrq=0; private var pwrLo=0; private var pwrHi=0

    private fun scale(deflt: Int, frm: Int, to: Int, mn: Int, mx: Int): Int {
        if (to == frm) return deflt
        val v = (deflt.toLong() * to / frm).toInt()
        return if (v < mn) mn else if (v > mx) mx else v
    }

    private fun W(idx: Int, sub: Int, v: Int): Boolean {
        val r = sc.write(idx, sub, v.toLong())
        if (!r.ok) log("  WRITE 0x%04X:%02X=%d FOUT %s".format(idx, sub, v, r.status))
        return r.ok
    }
    private fun Wtol(idx: Int, sub: Int, v: Int) {
        val r = sc.write(idx, sub, v.toLong())
        if (!r.ok) log("  (overslaan) 0x%04X:%02X=%d %s".format(idx, sub, v, r.status))
    }
    private fun readRaw(idx: Int, sub: Int): Pair<Boolean, Int> {
        val r = sc.read(idx, sub); return r.ok to r.value.toInt()
    }

    private fun readMaxPwr() {
        if (pwrLo == 0) {
            val (ok, rpm) = readRaw(0x4611, 0x04); val (okd, d) = readRaw(0x4611, 0x03)
            if (ok && okd) {
                if (d == PMAP[2] && rpm == PMAP[3]) pwrLo = PwrLo
                else if (rpm != 0) pwrLo = (((d * 1000) shr 4) * rpm + (9549 shr 1)) / 9549
            }
        }
        if (pwrHi == 0) {
            val (ok, rpm) = readRaw(0x4611, 0x12); val (okd, d) = readRaw(0x4611, 0x11)
            if (ok && okd) {
                if (d == PMAP[16] && rpm == PMAP[17]) pwrHi = PwrHi
                else if (rpm != 0) pwrHi = (((d * 1000) shr 4) * rpm + (9549 shr 1)) / 9549
            }
        }
    }

    private fun cfgSpeed(maxKphIn: Int, warnKphIn: Int): Boolean {
        val maxKph = if (maxKphIn == -1) KphMax else maxKphIn
        val warnKph = if (warnKphIn == -1) KphWarn else warnKphIn
        if (maxKph < 6 || warnKph < 6) return false
        if (maxTrq == 0) { val (ok, v) = readRaw(0x6076, 0x00); if (!ok) return false; maxTrq = v - MapTrq }
        readMaxPwr()
        var rpm = scale(RpmWarn, KphWarn, warnKph, 400, 65535)
        Wtol(0x3813, 0x34, rpm); Wtol(0x3813, 0x3c, rpm - WarnOff)
        rpm = scale(RpmMax, KphMax, maxKph, 400, 65535)
        if (!W(0x2920, 0x05, rpm)) return false
        if (!W(0x2920, 0x06, minOf(rpm, RpmRev))) return false
        Wtol(0x3813, 0x33, rpm + BrkStart); Wtol(0x3813, 0x35, rpm + BrkEnd); Wtol(0x3813, 0x3b, rpm + BrkDown)
        Wtol(0x3813, 0x2d, rpm + BrkDown + 1500); Wtol(0x4624, 0x00, rpm + BrkDown + 2500)
        maxRpm = rpm
        return true
    }

    private fun cfgPower(trqIn: Int, ploIn: Int, phiIn: Int, curIn: Int): Boolean {
        val limited = (curIn == -1)
        val cur = if (curIn == -1) 100 else curIn
        val trq = if (trqIn == -1) 100 else trqIn
        val plo = if (ploIn == -1) 100 else ploIn
        val phi = if (phiIn == -1) 100 else phiIn
        if (maxRpm == 0) { val (ok, v) = readRaw(0x2920, 0x05); if (!ok) return false; maxRpm = v }
        if (!W(0x4641, 0x02, scale(CurrStatorMax, 100, cur, 0, BoostCurr))) return false
        if (!W(0x6075, 0x00, scale(CurrLim, 100, cur, 0, BoostCurr * 1000))) return false
        maxTrq = scale(Trq, 100, trq, 10000, if (limited) TrqLim else 200000)
        if (!W(0x6076, 0x00, maxTrq + MapTrq)) return false
        if (!W(0x2916, 0x01, if (trq == 100) TrqRated else maxTrq + MapTrq)) return false
        pwrLo = scale(PwrLo, 100, plo, 500, if (limited) PwrLoLim else 200000)
        pwrHi = scale(PwrHi, 100, phi, 500, if (limited) PwrHiLim else 200000)
        val mmp = if (plo == 100 && phi == 100) MaxMotorPwr else (maxOf(pwrLo, pwrHi) * 0.353).toInt()
        Wtol(0x3813, 0x23, mmp)
        return true
    }

    private fun cfgMakePowermap(): Boolean {
        if (maxRpm == RpmMax && maxTrq == Trq && pwrLo == PwrLo && pwrHi == PwrHi) {
            for (i in 0 until 18) if (!W(0x4611, 0x01 + i, PMAP[i])) return false
            for (i in 0 until 4) if (!W(0x4610, 0x0f + i, FMAP[i])) return false
        } else {
            val rpm0 = (pwrLo * 9549 + (maxTrq shr 1)) / maxTrq
            val trq = (maxTrq * 16 + 500) / 1000
            if (!W(0x4611, 0x01, trq)) return false
            if (!W(0x4611, 0x02, 0)) return false
            if (!W(0x4611, 0x03, trq)) return false
            if (!W(0x4611, 0x04, rpm0)) return false
            val fmap = if (trq > FMAP[2]) EFMAP else FMAP
            for (i in 0 until 4) if (!W(0x4610, 0x0f + i, fmap[i])) return false
            val rpmD = if (maxRpm > rpm0) ((maxRpm - rpm0 + (FIB[6] shr 1)) / FIB[6]) else 0
            val pwrD = (pwrHi - pwrLo + (FIB[5] shr 1)) / FIB[5]
            for (i in 0 until 7) {
                val rpm = if (i < 6) rpm0 + FIB[i] * rpmD else maxRpm
                val pwr = if (i < 5) pwrLo + FIB[i] * pwrD else pwrHi
                val t = (((pwr * 9549 + (rpm shr 1)) / rpm) * 16 + 500) / 1000
                if (!W(0x4611, 0x05 + (i shl 1), t)) return false
                if (!W(0x4611, 0x06 + (i shl 1), rpm)) return false
            }
        }
        Wtol(0x4641, 0x01, 1)   // commit — sommige firmwares weigeren dit (tolerant)
        Thread.sleep(50)
        return true
    }

    private fun cfgDrive(prcIn: Int): Boolean {
        val prc = if (prcIn == -1) 100 else prcIn
        return W(0x2920, 0x01, minOf(prc * 10, 1000))
    }
    private fun cfgRecup(nIn: Int, bIn: Int): Boolean {
        val n = if (nIn == -1) RecupPrc else nIn
        val b = if (bIn == -1) RecupPrc else bIn
        if (!W(0x2920, 0x03, scale(Recup, RecupPrc, n, 0, 1000))) return false
        if (!W(0x2920, 0x04, scale(Recup, RecupPrc, b, 0, 1000))) return false
        return true
    }
    private fun cfgRamps(): Boolean {
        if (!W(0x291c, 0x02, scale(RampStart, RampStartPrm, RampStartPrm, 10, 10000))) return false
        if (!W(0x2920, 0x07, scale(RampAccel, RampAccelPrc, RampAccelPrc, 10, 10000))) return false
        if (!W(0x2920, 0x0b, scale(2000, 20, 20, 10, 10000))) return false
        if (!W(0x2920, 0x0d, scale(4000, 40, 40, 10, 10000))) return false
        if (!W(0x2920, 0x0e, scale(4000, 40, 40, 10, 10000))) return false
        return true
    }

    /** Retourneert "ok" of de stap waar het misging. */
    fun applyProfile(p: TuningProfile): String {
        maxRpm = 0; maxTrq = 0; pwrLo = 0; pwrHi = 0
        log("NMT Start…"); try { sc.nmtStart() } catch (_: Exception) {}
        log("Login…"); val (ok, st) = sc.login(); if (!ok) { log("  LOGIN FOUT: $st"); return "login" }
        log("Drive…"); if (!cfgDrive(p.drive)) return "drive"
        log("Recup…"); if (!cfgRecup(p.neutral, p.brake)) return "recup"
        log("Ramps…"); if (!cfgRamps()) return "ramps"
        log("Pre-operationeel…"); sc.enterCfg()
        var res = "ok"
        try {
            log("Snelheid…"); var okk = cfgSpeed(p.speed, p.warn)
            if (okk) { log("Vermogen…"); okk = cfgPower(p.torque, p.powerLow, p.powerHigh, p.current) }
            if (okk) { log("Powermap…"); okk = cfgMakePowermap() }
            if (!okk) res = "cfg"
        } finally {
            log("Operationeel herstellen…"); sc.leaveCfg()
        }
        return res
    }
}
