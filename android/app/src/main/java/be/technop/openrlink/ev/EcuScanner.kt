package be.technop.openrlink.ev

import be.technop.openrlink.elm.Elm327
import be.technop.openrlink.uds.UdsClient

data class ModuleDtc(val code: String, val status: Int, val description: String? = null, val udsStatus: Boolean = true) {
    val faultIndicated get() = udsStatus && status and 0x0D != 0
    val statusText get() = if (!udsStatus) "KWP-status; betekenis afhankelijk van ECU" else listOf(0x01 to "test faalt", 0x04 to "pending", 0x08 to "bevestigd", 0x20 to "sinds laatste wissen", 0x80 to "waarschuwingslamp")
        .filter { status and it.first != 0 }.joinToString { it.second }.ifEmpty { "geen foutindicatie in status" }
}
data class ModuleScan(
    val module: EcuModule,
    val state: String,
    val dtcs: List<ModuleDtc> = emptyList(),
    val identity: Map<String, String> = emptyMap(),
    val definition: String? = null,
    val raw: List<String> = emptyList()
) {
    fun lines(): List<String> = listOf("▶ ${module.target.name} (${module.id}): $state") +
        identity.map { "   ${it.key}: ${it.value}" } +
        (definition?.let { listOf("   ECU-definitie bevestigd: $it") } ?: emptyList()) +
        dtcs.map { "   • ${it.code} · %02X".format(it.status) + " (${it.statusText})" + (it.description?.let { d -> " → $d" } ?: "") }
}

object ModuleDtcDecoder {
    /** Null means malformed, never a successful empty fault list. */
    fun uds(data: List<Int>): List<ModuleDtc>? {
        if (data.size < 3 || data.take(2) != listOf(0x59, 2) || (data.size - 3) % 4 != 0) return null
        return data.drop(3).chunked(4).filter { it.take(3).any { b -> b != 0 } }.map {
            ModuleDtc(it.take(3).joinToString("") { b -> "%02X".format(b) }, it[3])
        }
    }
    fun kwp(data: List<Int>): List<ModuleDtc>? {
        if (data.size < 2 || data[0] != 0x57 || data.size != 2 + data[1] * 3) return null
        return data.drop(2).chunked(3).map {
            ModuleDtc("%c%d%X%02X".format("PCBU"[it[0] shr 6], (it[0] shr 4) and 3, it[0] and 15, it[1]), it[2], udsStatus = false)
        }
    }
}

class EcuScanner(private val elm: Elm327, private val database: EcuDatabase? = null) {
    fun scan(module: EcuModule, vehicle: EvVehicle, identify: Boolean, checkActive: () -> Unit = {}): ModuleScan {
        val raw = mutableListOf<String>()
        val fields = linkedMapOf<String, String>()
        val t = module.target
        elm.configUdsTarget(t.tx, t.rx)
        val uds = UdsClient(elm, t.rx)
        var enteredSession = false
        fun record(command: String, action: () -> UdsClient.Result): UdsClient.Result {
            checkActive()
            val r = action()
            raw.add("$command → ${r.raw}")
            return r
        }
        try {
            if (module.sessionRequired && module.session != null) {
                val session = record("10%02X".format(module.session)) { uds.diagnosticSession(module.session) }
                if (!session.ok) return ModuleScan(module, "sessie niet geopend (${session.nrc}); niet uitgelezen", raw = raw)
                enteredSession = true
            }
            var dtc = record("1902%02X".format(module.mask)) { uds.readDtcInformation(2, module.mask) }
            if (!dtc.ok && dtc.data.take(2) == listOf(0x7F, 0x19) && !enteredSession && module.session in listOf(3, 0xC0) && dtc.data.getOrNull(2) in listOf(0x7E, 0x7F)) {
                val session = record("10%02X".format(module.session)) { uds.diagnosticSession(module.session!!) }
                if (session.ok) {
                    enteredSession = true
                    dtc = record("1902%02X".format(module.mask)) { uds.readDtcInformation(2, module.mask) }
                }
            }
            if (dtc.data.take(2) == listOf(0x7F, 0x19) && dtc.data.getOrNull(2) == 0x31) {
                for (mask in module.readMasks.filter { it != module.mask }.take(3)) {
                    dtc = record("1902%02X".format(mask)) { uds.readDtcInformation(2, mask) }
                    if (dtc.ok || dtc.data.getOrNull(2) != 0x31) break
                }
            }
            var decoded = if (dtc.ok) ModuleDtcDecoder.uds(dtc.data) else null
            var protocol = "UDS"
            // Only a definitive unsupported-service/subfunction reply allows legacy fallback.
            if (!dtc.ok && dtc.data.take(2) == listOf(0x7F, 0x19) && dtc.data.getOrNull(2) in listOf(0x11, 0x12)) {
                val legacy = record("17FF00") { uds.readDtcStatusKwp() }
                decoded = if (legacy.ok) ModuleDtcDecoder.kwp(legacy.data) else null
                protocol = "KWP"
            }
            var reachable = dtc.data.isNotEmpty()
            // Read one standard part-number DID if the DTC service gives no reply.
            if (identify || !reachable) {
                val part = record("22F187") { uds.readDataByIdentifier(0xF187) }
                if (part.data.isNotEmpty()) reachable = true
                if (part.ok && part.data.size > 3) { fields["Onderdeelnummer"] = text(part.data.drop(3)); reachable = true }
            }
            var exactIdentity: EcuIdentity? = null
            if (identify && reachable) {
                if (!vehicle.isLeaf && module.variants.isNotEmpty()) {
                    val ids = listOf(0xF1A0, 0xF18A, 0xF194, 0xF195).map { did -> record("22%04X".format(did)) { uds.readDataByIdentifier(did) } }
                    if (ids.all { it.ok && it.data.size > 3 } && ids[0].data.size == 4) {
                        exactIdentity = EcuIdentity(ids[0].data[3].toString(), ascii(ids[1].data.drop(3)), ascii(ids[2].data.drop(3)), ascii(ids[3].data.drop(3)))
                        fields["Identificatie"] = "${exactIdentity.diagnostic} / ${exactIdentity.supplier} / ${exactIdentity.software} / ${exactIdentity.version}"
                    }
                } else {
                    for ((did, label) in listOf(0xF188 to "Software", 0xF18C to "Serienummer")) {
                        val value = record("22%04X".format(did)) { uds.readDataByIdentifier(did) }
                        if (value.ok && value.data.size > 3) fields[label] = text(value.data.drop(3))
                    }
                }
            }
            val variants = database?.matchingVariants(module, exactIdentity) ?: emptyList()
            val translated = decoded?.map { d -> d.copy(description = if (protocol == "UDS") database?.lookup(variants, d.code) else null) }
            val state = when {
                translated != null && translated.isEmpty() -> "$protocol beantwoord · geen DTC-records"
                translated != null -> "$protocol beantwoord · ${translated.size} records" + if (protocol == "UDS") ", ${translated.count { it.faultIndicated }} met foutindicatie" else ""
                reachable -> "module antwoordt; foutcodes niet volledig uitgelezen (${dtc.nrc ?: "ongeldige DTC-indeling"})"
                else -> "geen passend antwoord · afwezig, in slaap of niet bereikbaar via deze bus"
            }
            return ModuleScan(module, state, translated ?: emptyList(), fields, variants.takeIf { it.isNotEmpty() }?.joinToString { it.label }, raw)
        } finally {
            if (enteredSession) {
                val restored = uds.diagnosticSession(if (module.session == 0xC0) 0x81 else 1)
                check(restored.ok) { "Diagnosesessie kon niet worden afgesloten; verbreek en verbind opnieuw" }
            }
        }
    }
    companion object {
        private fun ascii(d: List<Int>): String = if (d.all { it == 0 || it in 0x20..0x7E }) d.filter { it != 0 }.map { it.toChar() }.joinToString("").trim() else ""
        private fun text(d: List<Int>): String = ascii(d).ifBlank { d.joinToString(" ") { "%02X".format(it) } }
    }
}
