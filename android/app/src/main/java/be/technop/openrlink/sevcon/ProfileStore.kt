package be.technop.openrlink.sevcon

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Custom profiles stay on the phone, independently of the USB connection. */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("custom_tuning_profiles", Context.MODE_PRIVATE)

    fun load(): List<TuningProfile> {
        val array = JSONArray(prefs.getString("profiles", "[]") ?: "[]")
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            TuningProfile(o.getString("name"), o.getInt("speed"), o.getInt("warn"),
                o.getInt("torque"), o.getInt("powerLow"), o.getInt("powerHigh"),
                o.getInt("current"), o.getInt("drive"), o.getInt("neutral"),
                o.getInt("brake"), o.optInt("rampStart", 40), o.optInt("rampAccel", 25))
                .also { require(ProfileRules.errors(it).isEmpty()) { "Ongeldig opgeslagen profiel" } }
        }.also { profiles ->
            require(profiles.map { it.name.lowercase(java.util.Locale.ROOT) }.distinct().size == profiles.size)
            require(profiles.none { p -> TuningProfiles.LIST.any { it.name.equals(p.name, true) } })
        }
    }

    fun save(profiles: List<TuningProfile>) {
        val array = JSONArray()
        profiles.forEach { p ->
            require(ProfileRules.errors(p).isEmpty())
            array.put(JSONObject().apply {
                put("name", p.name); put("speed", p.speed); put("warn", p.warn)
                put("torque", p.torque); put("powerLow", p.powerLow); put("powerHigh", p.powerHigh)
                put("current", p.current); put("drive", p.drive); put("neutral", p.neutral)
                put("brake", p.brake); put("rampStart", p.rampStart); put("rampAccel", p.rampAccel)
            })
        }
        check(prefs.edit().putString("profiles", array.toString()).commit()) { "Bewaren op telefoon is mislukt" }
    }
}
