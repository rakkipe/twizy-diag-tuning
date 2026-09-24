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
import be.technop.openrlink.transport.BluetoothSppTransport
import be.technop.openrlink.transport.DemoTransport
import be.technop.openrlink.transport.Transport
import kotlinx.coroutines.Dispatchers
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

    val dtcStored = mutableStateListOf<String>()
    val dtcPending = mutableStateListOf<String>()
    val live = mutableStateListOf<ObdService.LiveValue>()
    val ecuScanResults = mutableStateListOf<String>()
    val meters = mutableStateListOf<Pair<String, String>>()   // (label, waarde)
    val liveRunning = mutableStateOf(false)

    private val ioMutex = Mutex()          // serialiseert poort-toegang (live <-> acties)
    private var liveJob: Job? = null

    private val dtcDb by lazy { DtcDb(getApplication()) }
    val dtcDbReady = mutableStateOf(false)
    fun dtcDbPath(): String = dtcDb.expectedPath()

    /** Check (buiten de UI-thread) of de DTC-database leesbaar is. */
    fun refreshDtcDb() {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { dtcDb.available() }
            dtcDbReady.value = ok
        }
    }

    /** Kopieer een via de systeem-kiezer gekozen ecu.zip naar de interne app-map. */
    fun importEcuZip(input: java.io.InputStream) {
        viewModelScope.launch {
            busy.value = true
            logLine("ecu.zip kopiëren naar de app…")
            val ok = withContext(Dispatchers.IO) { dtcDb.importFrom(input) }
            dtcDbReady.value = ok
            logLine(if (ok) "✓ DTC-database geladen (${dtcDb.path})" else "✗ ecu.zip kon niet gelezen worden")
            busy.value = false
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

    fun connectDemo() = connectWith(DemoTransport())

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
        viewModelScope.launch {
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
                status.value = "Verbonden — ${t.name}"
                logLine("✓ Verbonden met ${t.name}")
            } catch (ex: Exception) {
                status.value = "Fout: ${ex.message}"
                logLine("✗ ${ex.message}")
                connected.value = false
            } finally { busy.value = false }
        }
    }

    fun disconnect() {
        liveJob?.cancel(); liveRunning.value = false
        viewModelScope.launch {
            withContext(Dispatchers.IO) { transport?.close() }
            connected.value = false; status.value = "Niet verbonden"
            logLine("Verbinding gesloten")
        }
    }

    fun scanDtcs() = io("DTC-scan") {
        val o = obd ?: return@io
        val stored = o.readStoredDtcs(); val pending = o.readPendingDtcs()
        withContext(Dispatchers.Main) {
            dtcStored.clear(); dtcStored.addAll(stored)
            dtcPending.clear(); dtcPending.addAll(pending)
            logLine("DTC's: ${stored.size} opgeslagen, ${pending.size} pending")
        }
    }

    fun clearDtcs() = io("DTC wissen") {
        val ok = obd?.clearDtcs() == true
        withContext(Dispatchers.Main) {
            if (ok) { dtcStored.clear(); dtcPending.clear() }
            logLine(if (ok) "✓ DTC's gewist" else "✗ Wissen mislukt")
        }
    }

    /** Multi-ECU DTC-scan over de Twizy-ECU's (UDS/KWP, read-only), met DTC→tekst
     *  uit de lokale ecu.zip. Start per ECU eerst een sessie (Renault fout-sectie 1081). */
    fun ecuScan() = io("ECU-scan") {
        val e = elm ?: return@io
        val uds = UdsClient(e)
        val results = ArrayList<String>()
        for (ecu in RenaultEcus.TWIZY) {
            e.configUdsTarget(ecu.txHeader, ecu.rxHeader)
            var sess = e.sendHex("1081")            // Renault 'StartOfSectionDefaut'
            if (!sess.contains("5081")) e.sendHex("10C0")   // fallback extended session
            val r = uds.readDtcInformation(0x02, 0xFF)
            val head = "▶ ${ecu.label} (${ecu.txHeader}/${ecu.rxHeader}): "
            if (r.ok && r.data.size >= 3 && r.data[0] == 0x59) {
                val body = r.data.drop(3)           // na 59 02 <mask>
                val dtcs = ArrayList<String>()
                var i = 0
                while (i + 3 < body.size) {
                    val code = "%02X%02X%02X".format(body[i], body[i + 1], body[i + 2])
                    val st = body[i + 3]
                    if (code != "000000") {
                        val txt = dtcDb.lookup(code)
                        dtcs.add("$code (st %02X)".format(st) + (txt?.let { " → $it" } ?: ""))
                    }
                    i += 4
                }
                results.add(head + if (dtcs.isEmpty()) "geen DTC's" else "${dtcs.size} DTC('s)")
                dtcs.forEach { results.add("   • $it") }
            } else if (r.nrc != null) {
                results.add(head + "geen DTC-dienst (${r.nrc})")
            } else {
                results.add(head + "geen antwoord")
            }
        }
        e.clearFilter()
        val note = if (dtcDb.available()) "DTC-tekst: uit ${dtcDb.path}"
                   else "DTC-tekst: geen ecu.zip gevonden — alleen codes (zie ECU-tab)"
        withContext(Dispatchers.Main) {
            ecuScanResults.clear(); ecuScanResults.addAll(results); ecuScanResults.add(note)
            logLine("ECU-scan klaar: ${results.size} regels")
        }
    }

    /** Namen van de beschikbare tuning-profielen (voor de UI-knoppen). */
    fun tuningProfileNames(): List<String> = TuningProfiles.LIST.map { it.name }

    /** Past een tuning-profiel toe via CANopen SDO. WRITE — alleen na bevestiging,
     *  auto stilstaand in N. Faalt veilig als de auto in GO staat. */
    fun applyTuning(profileName: String) = io("Tuning $profileName") {
        val e = elm ?: return@io
        val p = TuningProfiles.byName(profileName) ?: run {
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
        if (liveRunning.value) { liveJob?.cancel(); liveRunning.value = false; return }
        if (!connected.value) { logLine("Niet verbonden — meters"); return }
        liveRunning.value = true
        liveJob = viewModelScope.launch(Dispatchers.IO) {
            val e = elm ?: return@launch
            val sc = SevconCanopen(e)
            ioMutex.withLock { sc.configCanopen() }
            fun sgn(v: Long): Long = if (v and 0x80000000L != 0L) v - 0x100000000L else v
            while (isActive) {
                val out = ArrayList<Pair<String, String>>()
                ioMutex.withLock {
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
        val snap = obd?.snapshot() ?: emptyList()
        withContext(Dispatchers.Main) { live.clear(); live.addAll(snap); logLine("Live: ${snap.size}/${Pids.LIST.size} PID's") }
    }

    fun runTwizyAction(action: TwizyAction) = io("Twizy: ${action.title}") {
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
        val r = elm?.command(cmd) ?: "geen verbinding"
        withContext(Dispatchers.Main) {
            terminalOutput.value = "> $cmd\n$r\n\n" + terminalOutput.value
        }
    }

    private fun io(tag: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            if (!connected.value) { logLine("Niet verbonden — $tag overgeslagen"); return@launch }
            busy.value = true
            try { withContext(Dispatchers.IO) { ioMutex.withLock { block() } } }
            catch (e: Exception) { logLine("✗ $tag: ${e.message}") }
            finally { busy.value = false }
        }
    }
}
