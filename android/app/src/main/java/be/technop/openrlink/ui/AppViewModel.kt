package be.technop.openrlink.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import be.technop.openrlink.elm.Elm327
import be.technop.openrlink.ev.*
import be.technop.openrlink.obd.ObdService
import be.technop.openrlink.obd.Pids
import be.technop.openrlink.renault.TwizyAction
import be.technop.openrlink.renault.TwizyActions
import be.technop.openrlink.renault.RenaultEcus
import be.technop.openrlink.renault.DtcDb
import be.technop.openrlink.uds.UdsClient
import be.technop.openrlink.sevcon.SevconCanopen
import be.technop.openrlink.sevcon.SevconTuner
import be.technop.openrlink.sevcon.TuningProfiles
import be.technop.openrlink.sevcon.TuningProfile
import be.technop.openrlink.sevcon.ProfileRules
import be.technop.openrlink.sevcon.ProfileStore
import be.technop.openrlink.transport.BluetoothSppTransport
import be.technop.openrlink.transport.DemoTransport
import be.technop.openrlink.transport.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@SuppressLint("MissingPermission")
class AppViewModel(app: Application) : AndroidViewModel(app) {

    // --- State (Compose observeert deze) ---
    val status = mutableStateOf("Niet verbonden")
    val connected = mutableStateOf(false)
    val busy = mutableStateOf(false)
    val log = mutableStateListOf<String>()
    val customProfiles = mutableStateListOf<TuningProfile>()
    val profileMessage = mutableStateOf("")
    val profileDraft = mutableStateOf<TuningProfile?>(null)
    val editingProfileName = mutableStateOf<String?>(null)
    private val profileStore = ProfileStore(app)
    private var profileStorageReady = false

    init {
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { profileStore.load() }
                customProfiles.addAll(saved)
                profileStorageReady = true
            } catch (_: Exception) {
                profileMessage.value = "Eigen profielen konden niet worden gelezen. De opgeslagen gegevens blijven bewaard."
            }
        }
    }

    fun saveProfile(profile: TuningProfile, originalName: String? = null, done: () -> Unit) {
        if (!profileStorageReady) { profileMessage.value = "Profielopslag is nog niet beschikbaar."; return }
        val p = profile.copy(name = profile.name.trim())
        val errors = ProfileRules.errors(p).toMutableList()
        if (TuningProfiles.LIST.any { it.name.equals(p.name, true) }) errors.add("Kies een eigen naam; standaardprofielen blijven behouden.")
        if (customProfiles.any { it.name != originalName && it.name.equals(p.name, true) }) errors.add("Er bestaat al een eigen profiel met deze naam.")
        if (errors.isNotEmpty()) { profileMessage.value = errors.joinToString("\n"); return }
        if (busy.value) return
        val next = customProfiles.filterNot { it.name == originalName } + p
        viewModelScope.launch {
            busy.value = true
            try {
                withContext(Dispatchers.IO) { profileStore.save(next) }
                customProfiles.clear(); customProfiles.addAll(next)
                profileMessage.value = "${p.name} bewaard op deze telefoon."
                done()
            } catch (_: Exception) { profileMessage.value = "Bewaren mislukt; je vorige profielen zijn behouden." }
            finally { busy.value = false }
        }
    }

    fun deleteProfile(name: String) {
        if (!profileStorageReady || busy.value) return
        val next = customProfiles.filterNot { it.name == name }
        viewModelScope.launch {
            busy.value = true
            try {
                withContext(Dispatchers.IO) { profileStore.save(next) }
                customProfiles.clear(); customProfiles.addAll(next)
                profileMessage.value = "$name verwijderd van deze telefoon."
            } catch (_: Exception) { profileMessage.value = "Verwijderen mislukt; je profielen zijn behouden." }
            finally { busy.value = false }
        }
    }

    val dtcStored = mutableStateListOf<String>()
    val dtcPending = mutableStateListOf<String>()
    val live = mutableStateListOf<ObdService.LiveValue>()
    val ecuScanResults = mutableStateListOf<String>()
    val meters = mutableStateListOf<Pair<String, String>>()   // (label, waarde)
    val liveRunning = mutableStateOf(false)
    val vehicle = mutableStateOf(EvVehicle.TWIZY)
    val evSnapshot = mutableStateOf<EvSnapshot?>(null)
    val evProgress = mutableStateOf("")
    val evMessage = mutableStateOf("")
    val demo = mutableStateOf(false)
    val dtcPermanent = mutableStateListOf<String>()
    val obdReadStatus = mutableStateListOf<String>()

    fun selectVehicle(v: EvVehicle) {
        if (connected.value || busy.value || liveRunning.value || ecuDatabaseLoading.value) return
        vehicle.value = v
        clearReadings()
    }

    private fun clearReadings() {
        evSnapshot.value = null; evProgress.value = ""; evMessage.value = ""
        ecuScanResults.clear(); meters.clear(); live.clear()
        dtcStored.clear(); dtcPending.clear(); dtcPermanent.clear()
        obdReadStatus.clear()
        moduleScans.clear(); ecuModules.clear(); ecuScanProgress.value = ""
    }

    fun readEv(includeCells: Boolean = true) = io("EV-uitlezing") {
        val e = elm ?: return@io
        val v = vehicle.value
        if (v.isTwizy) return@io
        withContext(Dispatchers.Main) { evSnapshot.value = null; evMessage.value = "Uitlezen…" }
        val context = kotlin.coroutines.coroutineContext
        try {
            val snapshot = EvReader(e).read(v, includeCells, { context.ensureActive() }) { p ->
                viewModelScope.launch(Dispatchers.Main) { evProgress.value = p }
            }
            withContext(Dispatchers.Main) {
                evSnapshot.value = snapshot
                evMessage.value = if (snapshot.metrics.isEmpty() && snapshot.cells.isEmpty())
                    "Geen bruikbare metingen. Controleer voertuigkeuze, contact, adapter en CAN-toegang."
                    else "Momentopname klaar — ${snapshot.metrics.size} waarden, ${snapshot.cells.size}/96 cellen."
            }
        } catch (ex: Exception) {
            withContext(Dispatchers.Main) { evMessage.value = "Uitlezing mislukt: ${ex.message}" }
            throw ex
        } finally { withContext(Dispatchers.Main) { evProgress.value = "" } }
    }

    private val ioMutex = Mutex()          // serialiseert poort-toegang (live <-> acties)
    private var liveJob: Job? = null

    private val dtcDb by lazy { DtcDb(getApplication()) }
    private val ecuDatabase by lazy {
        val app = getApplication<Application>()
        EcuDatabase({ listOfNotNull(java.io.File(app.filesDir, "ecu.zip"), app.getExternalFilesDir(null)?.let { java.io.File(it, "ecu.zip") }) }, java.io.File(app.filesDir, "ecu.zip"))
    }
    val dtcDbReady = mutableStateOf(false)
    val ecuModules = mutableStateListOf<EcuModule>()
    val ecuDatabaseMessage = mutableStateOf("Database nog niet gecontroleerd")
    val ecuDatabaseLoading = mutableStateOf(false)
    val ecuScanning = mutableStateOf(false)
    val ecuScanProgress = mutableStateOf("")
    val identifyEcus = mutableStateOf(true)
    val moduleScans = mutableStateListOf<ModuleScan>()
    private val ecuStop = java.util.concurrent.atomic.AtomicBoolean(false)
    private class ScanStopped : RuntimeException()
    fun stopEcuScan() { ecuStop.set(true); ecuScanProgress.value = "Stoppen na het huidige antwoord…" }
    fun ecuReport(): String = buildString {
        appendLine("OpenRLink 1.6.0-preview — ECU-scan ${vehicle.value.label}")
        if (demo.value) appendLine("DEMO — FICTIEVE GEGEVENS")
        appendLine("Fysieke bereikbaarheid en volledigheid niet gevalideerd.")
        moduleScans.forEach { scan -> scan.lines().forEach { appendLine(it) }; scan.raw.forEach { appendLine(it) }; appendLine() }
    }
    fun dtcDbPath(): String = dtcDb.expectedPath()

    /** Check (buiten de UI-thread) of de DTC-database leesbaar is. */
    fun refreshDtcDb(force: Boolean = false) {
        if (ecuDatabaseLoading.value || busy.value || liveRunning.value) return
        viewModelScope.launch {
            ecuDatabaseLoading.value = true
            val v = vehicle.value
            try {
                val modules = withContext(Dispatchers.IO) {
                    if (force) { ecuDatabase.reset(); dtcDb.reset() }
                    EcuModules.merge(v, ecuDatabase.modules(v))
                }
                if (vehicle.value == v) {
                    ecuModules.clear(); ecuModules.addAll(modules)
                    dtcDbReady.value = ecuDatabase.available()
                    ecuDatabaseMessage.value = ecuDatabase.message
                }
            } catch (ex: Exception) {
                ecuModules.clear(); ecuModules.addAll(EcuModules.builtIn(v))
                ecuDatabaseMessage.value = "Database niet ingelezen: ${ex.message}; gepubliceerde modulelijst beschikbaar"
            } finally { ecuDatabaseLoading.value = false }
        }
    }

    /** Kopieer een via de systeem-kiezer gekozen ecu.zip naar de interne app-map. */
    fun importEcuZip(input: java.io.InputStream) {
        if (busy.value || liveRunning.value || ecuDatabaseLoading.value) { input.close(); return }
        viewModelScope.launch {
            busy.value = true
            logLine("ecu.zip kopiëren naar de app…")
            val ok = withContext(Dispatchers.IO) { ecuDatabase.importFrom(input) }
            dtcDb.reset()
            dtcDbReady.value = ok
            logLine(if (ok) "✓ DTC-database geladen (${dtcDb.path})" else "✗ ecu.zip kon niet gelezen worden")
            busy.value = false
            refreshDtcDb()
        }
    }

    val terminalOutput = mutableStateOf("")

    private var transport: Transport? = null
    private var elm: Elm327? = null
    private var obd: ObdService? = null
    private var twizy: TwizyActions? = null

    fun bondedDevices(): List<BluetoothDevice> {
        val mgr = getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = mgr?.adapter
        return try { adapter?.bondedDevices?.toList() ?: emptyList() } catch (e: SecurityException) { emptyList() }
    }

    private fun logLine(s: String) { log.add(0, s); if (log.size > 200) log.removeAt(log.size - 1) }

    fun connectDemo() = connectWith(DemoTransport(vehicle.value))

    fun connectBluetooth(device: BluetoothDevice) = connectWith(BluetoothSppTransport(device))

    /** USB-OTG naar vLinker FS. Regelt permissie; bij eerste keer 2x drukken. */
    fun connectUsb() {
        val t = be.technop.openrlink.transport.UsbSerialTransport(getApplication())
        if (!t.deviceAvailable()) { logLine("✗ Geen USB-adapter gezien — OTG-kabel + vLinker?"); return }
        if (!t.ensurePermission()) { logLine("USB-permissie gevraagd — sta toe en druk opnieuw"); return }
        connectWith(t)
    }

    /** TCP naar M5StickC WiFi-CAN brug. */
    fun connectTcp(host: String, port: Int) =
        connectWith(be.technop.openrlink.transport.TcpTransport(host, port))

    private fun connectWith(t: Transport) {
        if (busy.value || connected.value || ecuDatabaseLoading.value) return
        viewModelScope.launch {
            clearReadings()
            busy.value = true; status.value = "Verbinden met ${t.name}…"
            try {
                withContext(Dispatchers.IO) {
                    t.open()
                    val e = Elm327(t)
                    val ok = e.initObd()
                    transport = t; elm = e
                    obd = ObdService(e); twizy = TwizyActions(e)
                    if (!ok) throw IllegalStateException("Adapter reageerde niet als ELM/STN")
                }
                connected.value = true
                demo.value = t is DemoTransport
                status.value = "Verbonden — ${t.name}"
                logLine("✓ Verbonden met ${t.name}")
            } catch (ex: Exception) {
                withContext(Dispatchers.IO) { t.close() }
                transport = null; elm = null; obd = null; twizy = null
                status.value = "Fout: ${ex.message}"
                logLine("✗ ${ex.message}")
                connected.value = false
            } finally { busy.value = false }
        }
    }

    fun disconnect() {
        if (busy.value) return
        liveJob?.cancel(); liveRunning.value = false
        viewModelScope.launch {
            busy.value = true
            withContext(Dispatchers.IO) { ioMutex.withLock { transport?.close(); transport = null; elm = null; obd = null; twizy = null } }
            connected.value = false; status.value = "Niet verbonden"
            demo.value = false; clearReadings(); busy.value = false
            logLine("Verbinding gesloten")
        }
    }

    fun scanDtcs() = io("DTC-scan") {
        val o = obd ?: return@io
        withContext(Dispatchers.Main) { dtcStored.clear(); dtcPending.clear(); dtcPermanent.clear(); obdReadStatus.clear() }
        elm?.configGenericObd()
        val operations = listOf(
            Triple("Opgeslagen", { o.readStoredDtcs() }, dtcStored),
            Triple("Pending", { o.readPendingDtcs() }, dtcPending),
            Triple("Permanent", { o.readPermanentDtcs() }, dtcPermanent)
        )
        for ((name, read, destination) in operations) {
            try {
                val codes = read()
                withContext(Dispatchers.Main) { destination.addAll(codes); obdReadStatus.add("$name: ${codes.size} codes (dienst beantwoord)") }
            } catch (ex: IllegalArgumentException) {
                withContext(Dispatchers.Main) { obdReadStatus.add("$name: niet uitgelezen — ${ex.message}") }
            }
        }
        withContext(Dispatchers.Main) { logLine("OBD-scan klaar; zie de status per dienst.") }
    }

    fun clearDtcs() = io("DTC wissen") {
        if (!vehicle.value.isTwizy) return@io
        elm?.configGenericObd()
        val ok = obd?.clearDtcs() == true
        withContext(Dispatchers.Main) {
            if (ok) { dtcStored.clear(); dtcPending.clear() }
            logLine(if (ok) "✓ DTC's gewist" else "✗ Wissen mislukt")
        }
    }

    /** Multi-ECU DTC-scan over de Twizy-ECU's (UDS/KWP, read-only), met DTC→tekst
     *  uit de lokale ecu.zip. Start per ECU eerst een sessie (Renault fout-sectie 1081). */
    fun ecuScan(moduleId: String? = null) = io("ECU-scan") {
        val e = elm ?: return@io
        val v = vehicle.value
        if (!v.isTwizy) { scanEvModules(e, v, moduleId); return@io }
        withContext(Dispatchers.Main) { ecuScanResults.clear() }
        val results = ArrayList<String>()
        val targets = if (v.isTwizy) RenaultEcus.TWIZY.filter { it.key != "PEB" }
            .map { EcuTarget(it.label, it.txHeader, it.rxHeader) } else v.scanTargets
        for (ecu in targets) {
            e.configUdsTarget(ecu.tx, ecu.rx)
            val uds = UdsClient(e, ecu.rx)
            if (v.isTwizy) {
                if (!uds.diagnosticSession(0x81).ok) uds.diagnosticSession(0xC0)
            }
            val r = uds.readDtcInformation(0x02, 0xFF)
            val head = "▶ ${ecu.name} (${ecu.tx}/${ecu.rx}): "
            if (r.ok && r.data.size >= 3 && r.data.take(2) == listOf(0x59, 0x02) && (r.data.size - 3) % 4 == 0) {
                val body = r.data.drop(3)           // na 59 02 <mask>
                val dtcs = ArrayList<String>()
                var i = 0
                while (i + 3 < body.size) {
                    val code = "%02X%02X%02X".format(body[i], body[i + 1], body[i + 2])
                    val st = body[i + 3]
                    if (code != "000000") {
                        val txt = if (v.isTwizy) dtcDb.lookup(code) else null
                        dtcs.add("$code (st %02X)".format(st) + (txt?.let { " → $it" } ?: ""))
                    }
                    i += 4
                }
                results.add(head + if (dtcs.isEmpty()) "geen DTC's" else "${dtcs.size} DTC('s)")
                dtcs.forEach { results.add("   • $it") }
            } else if (r.nrc != null) {
                results.add(head + "niet uitgelezen (${r.nrc})")
            } else {
                results.add(head + "ongeldig/onvolledig antwoord")
            }
        }
        val note = if (v.isTwizy && dtcDb.available()) "DTC-tekst: uit ${dtcDb.path}"
                   else "Ruwe ECU-codes; geen bevestigde voertuiggebonden omschrijving beschikbaar."
        withContext(Dispatchers.Main) {
            ecuScanResults.clear(); ecuScanResults.addAll(results); ecuScanResults.add(note)
            logLine("ECU-scan klaar: ${results.size} regels")
        }
    }

    private suspend fun scanEvModules(e: Elm327, v: EvVehicle, moduleId: String?) {
        val available = EcuModules.merge(v, ecuDatabase.modules(v))
        val modules = if (moduleId == null) available else available.filter { it.id == moduleId }
        val identify = identifyEcus.value
        ecuStop.set(false)
        withContext(Dispatchers.Main) {
            ecuScanning.value = true; moduleScans.clear(); ecuScanResults.clear()
            ecuModules.clear(); ecuModules.addAll(available)
        }
        try {
            val scanner = EcuScanner(e, ecuDatabase)
            val context = kotlin.coroutines.coroutineContext
            for ((i, module) in modules.withIndex()) {
                if (ecuStop.get()) break
                withContext(Dispatchers.Main) { ecuScanProgress.value = "${i + 1}/${modules.size}: ${module.target.name}" }
                val result = scanner.scan(module, v, identify) { context.ensureActive(); if (ecuStop.get()) throw ScanStopped() }
                withContext(Dispatchers.Main) { moduleScans.add(result); ecuScanResults.addAll(result.lines()) }
            }
            withContext(Dispatchers.Main) { ecuScanProgress.value = if (ecuStop.get()) "Gestopt — ${moduleScans.size}/${modules.size} modules afgewerkt" else "Klaar — ${moduleScans.size} modules bevraagd" }
        } catch (_: ScanStopped) {
            withContext(Dispatchers.Main) { ecuScanProgress.value = "Gestopt — vorige module-antwoorden blijven beschikbaar" }
        } catch (ex: Exception) {
            withContext(Dispatchers.Main) { ecuScanProgress.value = "Scan afgebroken: ${ex.message}. Verbreek en verbind opnieuw." }
            throw ex
        } finally { withContext(Dispatchers.Main) { ecuScanning.value = false } }
    }

    /** Namen van de beschikbare tuning-profielen (voor de UI-knoppen). */
    fun tuningProfileNames(): List<String> = (TuningProfiles.LIST + customProfiles).map { it.name }

    fun profileByName(name: String): TuningProfile? =
        TuningProfiles.byName(name) ?: customProfiles.firstOrNull { it.name == name }

    /** Past een tuning-profiel toe via CANopen SDO. WRITE — alleen na bevestiging,
     *  auto stilstaand in N. Faalt veilig als de auto in GO staat. */
    fun applyTuning(profileName: String) = io("Tuning $profileName") {
        check(vehicle.value.isTwizy) { "SEVCON-tuning is uitsluitend voor Twizy" }
        val e = elm ?: return@io
        val p = profileByName(profileName) ?: run {
            withContext(Dispatchers.Main) { logLine("Onbekend profiel $profileName") }; return@io
        }
        withContext(Dispatchers.Main) { logLine("=== Profiel $profileName toepassen ===") }
        val sc = SevconCanopen(e)
        sc.configCanopen()
        val tuner = SevconTuner(sc) { line -> viewModelScope.launch(Dispatchers.Main) { logLine(line) } }
        val res = tuner.applyProfile(p)
        withContext(Dispatchers.Main) {
            logLine(
                if (res == "ok") "✓ $profileName toegepast"
                else "✗ FOUT bij stap: $res (auto in N? niet in GO?)"
            )
        }
    }

    /** Live SEVCON-meters (OVMS-meetblok 0x4600/0x4602) — start/stop poll-lus. */
    fun toggleLiveMeters() {
        if (liveRunning.value) { liveJob?.cancel(); if (vehicle.value.isTwizy) liveRunning.value = false; return }
        if (busy.value) return
        if (!connected.value) { logLine("Niet verbonden — meters"); return }
        if (!vehicle.value.isTwizy) { startEvLive(); return }
        liveRunning.value = true
        liveJob = viewModelScope.launch(Dispatchers.IO) {
            val e = elm ?: return@launch
            val sc = SevconCanopen(e)
            ioMutex.withLock { sc.configCanopen() }
            fun sgn(v: Long): Long = if (v and 0x80000000L != 0L) v - 0x100000000L else v
            while (isActive) {
                val out = ArrayList<Pair<String, String>>()
                ioMutex.withLock {
                    sc.configCanopen()
                    fun rd(idx: Int, sub: Int): Long? { val r = sc.read(idx, sub); return if (r.ok) sgn(r.value) else null }
                    val bat = rd(0x4602, 0x11); out.add("Accu-spanning" to (bat?.let { "%.1f V".format(it / 16.0) } ?: "—"))
                    val cap = rd(0x4602, 0x12); out.add("Cap-spanning" to (cap?.let { "%.1f V".format(it / 16.0) } ?: "—"))
                    val cur = rd(0x4600, 0x0c); out.add("Motorstroom" to (cur?.let { "$it A" } ?: "—"))
                    val mv = rd(0x4600, 0x0d); out.add("Motorspanning" to (mv?.let { "%.1f V".format(it / 16.0) } ?: "—"))
                    out.add("Motorvermogen" to (if (cur != null && mv != null) "%.2f kW".format((mv / 16.0) * cur / 1000.0) else "—"))
                    val trq = rd(0x4602, 0x0c); out.add("Koppel actueel" to (trq?.let { "%.1f Nm".format(it / 16.0) } ?: "—"))
                    val trl = rd(0x4602, 0x0e); out.add("Koppel-limiet" to (trl?.let { "%.1f Nm".format(it / 16.0) } ?: "—"))
                    val frq = rd(0x4600, 0x0f); out.add("Uitgangsfreq." to (frq?.let { "%.1f rad/s".format(it / 16.0) } ?: "—"))
                }
                withContext(Dispatchers.Main) { meters.clear(); meters.addAll(out) }
                delay(1000)
            }
        }
    }

    fun readLive() = io("Live snapshot") {
        elm?.configGenericObd()
        val snap = obd?.snapshot() ?: emptyList()
        withContext(Dispatchers.Main) { live.clear(); live.addAll(snap); logLine("Live: ${snap.size}/${Pids.LIST.size} PID's") }
    }

    fun runTwizyAction(action: TwizyAction) = io("Twizy: ${action.title}") {
        check(vehicle.value.isTwizy) { "Twizy-actie niet beschikbaar voor dit voertuig" }
        val res = twizy?.execute(action)
        withContext(Dispatchers.Main) {
            val txt = when {
                res == null -> "geen client"
                res.ok -> "OK → ${res.raw}"
                else -> "NRC: ${res.nrc} (${res.raw})"
            }
            logLine("${action.title}: $txt")
        }
    }

    fun sendRaw(cmd: String) = io("RAW $cmd") {
        check(vehicle.value.isTwizy) { "Terminal is uitgeschakeld tijdens Zoe/Leaf-uitlezing" }
        val r = elm?.command(cmd) ?: "geen verbinding"
        withContext(Dispatchers.Main) {
            terminalOutput.value = "> $cmd\n$r\n\n" + terminalOutput.value
        }
    }

    private fun io(tag: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            if (busy.value || liveRunning.value || ecuDatabaseLoading.value) { logLine("Stop live of wacht op de huidige actie — $tag"); return@launch }
            if (!connected.value) { logLine("Niet verbonden — $tag overgeslagen"); return@launch }
            busy.value = true
            try { withContext(Dispatchers.IO) { ioMutex.withLock { block() } } }
            catch (e: Exception) { logLine("✗ $tag: ${e.message}") }
            finally { busy.value = false }
        }
    }

    private fun startEvLive() {
        val v = vehicle.value
        liveRunning.value = true
        liveJob = viewModelScope.launch {
            try {
                while (isActive) {
                    evSnapshot.value = null; evMessage.value = "Nieuwe live-meting…"
                    val context = kotlin.coroutines.coroutineContext
                    val snapshot = withContext(Dispatchers.IO) {
                        ioMutex.withLock { EvReader(elm ?: error("Verbinding gesloten")).read(v, false, { context.ensureActive() }) }
                    }
                    evSnapshot.value = snapshot
                    evMessage.value = if (snapshot.metrics.isEmpty()) "Geen bruikbare live-gegevens; controleer contact en CAN-toegang." else "Live-momentopname"
                    delay(10000)
                }
            } catch (ex: CancellationException) { throw ex }
            catch (ex: Exception) { evSnapshot.value = null; evMessage.value = "Live gestopt: ${ex.message}"; logLine(evMessage.value) }
            finally { liveRunning.value = false }
        }
    }

    override fun onCleared() {
        liveJob?.cancel()
        transport?.close()
        super.onCleared()
    }
}
