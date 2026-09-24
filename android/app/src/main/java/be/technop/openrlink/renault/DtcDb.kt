package be.technop.openrlink.renault

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Vertaalt DTC-codes naar tekst via een LOKALE DDT4All-`ecu.zip` die de gebruiker
 * zelf op de telefoon plaatst. De app bundelt zelf GEEN database — hij leest jouw
 * bestand at runtime, net zoals DDT4All. Zonder bestand tonen we gewoon de code.
 *
 * Plaats ecu.zip op één van deze plekken (eerste die bestaat wint):
 *   1) Android/data/be.technop.openrlink/files/ecu.zip   (geen permissie nodig)
 *   2) /sdcard/Download/ecu.zip
 */
class DtcDb(private val context: Context) {

    private var map: HashMap<Int, String>? = null
    var path: String? = null
        private set

    /** Interne kopie (altijd leesbaar door de app) — voorkeurslocatie. */
    private fun internalFile(): File = File(context.filesDir, "ecu.zip")

    private fun candidates(): List<File> {
        val list = ArrayList<File>()
        list.add(internalFile())                                            // interne kopie eerst
        context.getExternalFilesDir(null)?.let { list.add(File(it, "ecu.zip")) }
        list.add(File("/storage/emulated/0/Download/ecu.zip"))
        list.add(File("/sdcard/Download/ecu.zip"))
        return list
    }

    /** Waar het bestand terechtkomt na 'Kies ecu.zip' (voor UI-tekst). */
    fun expectedPath(): String = internalFile().absolutePath

    /** Vergeet de cache zodat een volgende check opnieuw laadt (na import). */
    fun reset() { map = null; path = null }

    /** Kopieert een gekozen ecu.zip naar de interne app-map en herlaadt. */
    fun importFrom(input: InputStream): Boolean {
        return try {
            val dest = internalFile()
            input.use { i -> dest.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
            reset()
            available()
        } catch (_: Exception) {
            false
        }
    }

    private fun load() {
        if (map != null) return
        val m = HashMap<Int, String>()
        map = m
        candidates().forEach { c ->
            android.util.Log.i("DtcDb", "kandidaat ${c.absolutePath} exists=${c.exists()} size=${if (c.exists()) c.length() else -1}")
        }
        // Probeer elke kandidaat tot er één een GELDIGE zip met codes oplevert.
        // (Zo slaan we een afgekapte/kapotte kopie over en vallen we terug op de volledige.)
        for (f in candidates()) {
            if (!f.exists() || f.length() <= 0L) continue
            m.clear()
            try {
                ZipFile(f).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        val n = e.name
                        if (n.endsWith(".json") && !n.endsWith(".layout") &&
                            (n.contains("X09") || n.contains("Twizy"))
                        ) {
                            try {
                                val txt = zip.getInputStream(e).bufferedReader().use { it.readText() }
                                val lists = JSONObject(txt)
                                    .optJSONObject("data")
                                    ?.optJSONObject("DTCDeviceIdentifier")
                                    ?.optJSONObject("lists")
                                if (lists != null) {
                                    val keys = lists.keys()
                                    while (keys.hasNext()) {
                                        val k = keys.next()
                                        k.toIntOrNull()?.let { m[it] = lists.optString(k) }
                                    }
                                }
                            } catch (_: Exception) { /* sla defecte entry over */ }
                        }
                    }
                }
            } catch (ex: Exception) {
                android.util.Log.w("DtcDb", "kan ${f.absolutePath} niet lezen: ${ex.message}")
                m.clear()
                continue
            }
            if (m.isNotEmpty()) {
                path = f.absolutePath
                android.util.Log.i("DtcDb", "geladen uit ${f.absolutePath}: ${m.size} codes")
                return
            }
        }
        android.util.Log.w("DtcDb", "geen bruikbare ecu.zip (alle kandidaten leeg/kapot)")
        android.util.Log.i("DtcDb", "totaal geladen codes = ${m.size}")
    }

    fun available(): Boolean { load(); return map?.isNotEmpty() == true }

    /** codeHex = 3-byte UDS DTC (6 hex). Retourneert klachttekst of null. */
    fun lookup(codeHex: String): String? {
        load()
        val m = map ?: return null
        if (m.isEmpty()) return null
        val h = codeHex.filter { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }.uppercase()
        val cands = ArrayList<Int>()
        try {
            if (h.length >= 4) {
                cands.add(h.substring(0, 4).toInt(16))                      // eerste 2 bytes
                cands.add((h.substring(2, 4) + h.substring(0, 2)).toInt(16)) // byte-swap
            }
            if (h.length >= 6) cands.add(h.toInt(16))                        // volledige 3 bytes
        } catch (_: Exception) { return null }
        for (c in cands) m[c]?.let { return it }
        return null
    }
}
