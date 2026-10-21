package be.technop.openrlink.ev

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** User-owned DDT4All JSON archive; never bundled, extracted or uploaded. */
class EcuDatabase(private val candidates: () -> List<File>, private val destination: File) {
    private val cache = mutableMapOf<EvVehicle, List<EcuModule>>()
    private var selectedFile: File? = null
    var message: String = "Nog niet gecontroleerd"
        private set
    val path get() = selectedFile?.absolutePath
    fun reset() { cache.clear(); selectedFile = null }

    private fun archive(): File? {
        selectedFile?.let { if (it.isFile) return it }
        for (file in candidates()) try {
            if (file.isFile) ZipFile(file).use { zip ->
                val index = zip.getEntry("db.json") ?: return@use
                if (index.size in 1..MAX_INDEX.toLong()) { selectedFile = file; return file }
            }
        } catch (_: Exception) { /* try the next readable archive */ }
        return null
    }
    fun available(): Boolean = archive() != null

    @Synchronized
    fun importFrom(input: InputStream): Boolean {
        val temp = File(destination.parentFile, "ecu-import.tmp")
        return try {
            input.use { i -> temp.outputStream().use { i.copyTo(it) } }
            ZipFile(temp).use { zip -> readJson(zip, "db.json", MAX_INDEX) }
            // Atomic replacement on Android, on the same filesystem; keep the previous file on failure.
            java.nio.file.Files.move(temp.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            reset(); message = "ecu.zip geïmporteerd"; true
        } catch (ex: Exception) { message = "Import mislukt: ${ex.message}"; false }
        finally { temp.delete() }
    }

    @Synchronized
    fun modules(vehicle: EvVehicle): List<EcuModule> {
        cache[vehicle]?.let { return it }
        val file = archive() ?: run { message = "Geen leesbare ecu.zip met db.json"; return emptyList() }
        if (vehicle.isLeaf) { message = "ecu.zip beschikbaar; deze Renault-database levert geen Leaf-definities"; return emptyList() }
        val project = when (vehicle) { EvVehicle.ZOE_PH1 -> "x10"; EvVehicle.ZOE_PH2 -> "x10ph2"; else -> "x09" }
        val byTarget = linkedMapOf<String, EcuModule>()
        var skipped = 0
        ZipFile(file).use { zip ->
            val index = readJson(zip, "db.json", MAX_INDEX)
            val keys = index.keys()
            while (keys.hasNext()) {
                val entryName = keys.next()
                val meta = index.optJSONObject(entryName) ?: continue
                val projects = meta.optJSONArray("projects") ?: continue
                if ((0 until projects.length()).none { projects.optString(it).equals(project, true) }) continue
                if (!meta.optString("protocol").equals("CAN", true)) { skipped++; continue }
                if (meta.optString("group").contains("CAN", true)) continue // bus definitions / adapter, no real module
                try {
                    val definition = readJson(zip, entryName)
                    val obd = definition.optJSONObject("obd") ?: continue
                    if (!obd.optString("protocol").equals("CAN", true)) continue
                    val tx = obd.optString("send_id").uppercase(); val rx = obd.optString("recv_id").uppercase()
                    if (!EcuModules.validPair(tx, rx)) { skipped++; continue }
                    val identities = mutableListOf<EcuIdentity>()
                    val ids = meta.optJSONArray("autoidents")
                    if (ids != null) for (i in 0 until ids.length()) {
                        val id = ids.optJSONObject(i) ?: continue
                        identities.add(EcuIdentity(id.optString("diagnostic_version"), id.optString("supplier_code"), id.optString("soft_version"), id.optString("version")))
                    }
                    val variant = EcuVariant(entryName, meta.optString("ecuname", entryName), identities)
                    val target = EcuTarget(meta.optString("group", obd.optString("funcname", "ECU")), tx, rx)
                    val requests = definition.optJSONArray("requests")
                    val masks = if (requests == null) emptyList() else (0 until requests.length()).mapNotNull { i ->
                        requests.optJSONObject(i)?.optString("sentbytes")?.uppercase()?.takeIf { it.matches(Regex("1902[0-9A-F]{2}")) }?.takeLast(2)?.toInt(16)
                    }.distinct().sorted()
                    val module = EcuModule(target, source = "Eigen ecu.zip · project $project", variants = listOf(variant), readMasks = masks,
                        session = if (vehicle == EvVehicle.ZOE_PH1) 0xC0 else 3)
                    val old = byTarget[module.id]
                    byTarget[module.id] = if (old == null) module else old.copy(variants = old.variants + variant, readMasks = (old.readMasks + masks).distinct().sorted())
                } catch (_: Exception) { skipped++ }
            }
        }
        val result = byTarget.values.toList()
        cache[vehicle] = result
        message = "${result.size} moduleadressen, ${result.sumOf { it.variants.size }} varianten uit ecu.zip ($project); $skipped niet bruikbaar"
        return result
    }

    /** Only an exact, complete identification match may supply DTC descriptions. */
    fun matchingVariants(module: EcuModule, actual: EcuIdentity?): List<EcuVariant> {
        if (actual?.complete != true) return emptyList()
        return module.variants.filter { variant -> variant.identities.any { expected ->
            expected.complete && expected.diagnostic.toIntOrNull() == actual.diagnostic.toIntOrNull() &&
                expected.supplier.equals(actual.supplier, true) && expected.software.equals(actual.software, true) && expected.version.equals(actual.version, true)
        } }
    }

    fun lookup(variants: List<EcuVariant>, code: String): String? {
        if (variants.isEmpty() || !code.matches(Regex("[0-9A-F]{6}"))) return null
        val file = archive() ?: return null
        return ZipFile(file).use { zip ->
            val answers = variants.map { variant ->
                val definition = readJson(zip, variant.file)
                val data = definition.optJSONObject("data") ?: return@map null
                val values = listOf("DTCDeviceIdentifier", "DTCDeviceAndFailureTypeOBD").mapNotNull { name ->
                    val field = data.optJSONObject(name) ?: return@mapNotNull null
                    // UDS record: first two bytes are device identifier; no byte-swap guessing.
                    if (field.optInt("bitscount", 16) != 16) return@mapNotNull null
                    field.optJSONObject("lists")?.optString(code.take(4).toInt(16).toString())?.takeIf { it.isNotBlank() }
                }.distinct()
                values.singleOrNull()
            }
            // Conflicting or missing mappings across matched definitions stay untranslated.
            if (answers.all { !it.isNullOrBlank() } && answers.distinct().size == 1) answers.first() else null
        }
    }

    companion object {
        private const val MAX_INDEX = 24 * 1024 * 1024
        private fun readJson(zip: ZipFile, name: String, limit: Int = 12 * 1024 * 1024): JSONObject {
            val entry = zip.getEntry(name) ?: error("Ontbrekende database-entry")
            require(entry.size in 1..limit.toLong()) { "Database-entry te groot of leeg" }
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            require(bytes.size <= limit)
            return JSONObject(bytes.toString(Charsets.UTF_8))
        }
    }
}
