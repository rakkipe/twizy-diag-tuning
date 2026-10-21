package be.technop.openrlink.sevcon

/** Limits for the profile editor; no vehicle access or register writes. */
object ProfileRules {
    fun errors(p: TuningProfile): List<String> = buildList {
        if (p.name.isBlank() || p.name.length > 40 || p.name.any { it.isISOControl() })
            add("Kies een profielnaam van 1 tot 40 tekens.")
        if (p.speed != -1 && p.speed !in 6..120) add("Snelheid: 6–120 km/u of fabriekswaarde.")
        if (p.warn != -1 && p.warn !in 6..130) add("Snelheidsmelding: 6–130 km/u of fabriekswaarde.")
        if ((if (p.warn == -1) 89 else p.warn) < (if (p.speed == -1) 80 else p.speed))
            add("Zet de snelheidsmelding minstens gelijk aan de maximumsnelheid.")
        if (p.torque !in 10..150) add("Koppel: 10–150%.")
        if (p.powerLow !in 10..140 || p.powerHigh !in 10..140) add("Vermogen: 10–140%.")
        if (p.current !in 10..120) add("Stroomlimiet: 10–120%.")
        if (p.drive !in 0..100) add("Aandrijfkoppel: 0–100%.")
        if (p.neutral !in 0..100 || p.brake !in 0..100) add("Regeneratie: 0–100%.")
        if (p.rampStart !in 1..100) add("Starthelling: 1–100‰.")
        if (p.rampAccel !in 1..100) add("Koppelopbouw: 1–100%.")
        // The existing engine builds an ordered curve from this knee to max RPM.
        // Reject combinations that would put the knee beyond the final curve point.
        if ((p.speed == -1 || p.speed in 6..120) && p.torque in 10..150 && p.powerLow in 10..140) {
            val rpm = (7250L * (if (p.speed == -1) 80 else p.speed) / 80).coerceIn(400, 65535)
            val torque = (55000L * p.torque / 100).coerceIn(10000, 200000)
            val power = (12182L * p.powerLow / 100).coerceIn(500, 200000)
            val knee = power * 9549 / torque
            if (knee >= rpm) add("Deze combinatie past niet in de vermogenscurve: verlaag laagtoerig vermogen of verhoog koppel/snelheid.")
        }
    }

    fun summary(p: TuningProfile): String =
        "Snelheid ${if (p.speed == -1) "fabriek" else "${p.speed} km/u"} · " +
        "koppel ${p.torque}% · stroom ${p.current}%\n" +
        "Vermogen laag/hoog ${p.powerLow}/${p.powerHigh}% · aandrijving ${p.drive}%\n" +
        "Gas-los/remregeneratie ${p.neutral}/${p.brake}%\n" +
        "Starthelling ${p.rampStart}‰ · koppelopbouw ${p.rampAccel}%"
}
