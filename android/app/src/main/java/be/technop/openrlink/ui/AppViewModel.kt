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
import be.technop.openrlink.transport.BluetoothSppTransport
import be.technop.openrlink.transport.DemoTransport
import be.technop.openrlink.transport.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: Exception) { logLine("✗ $tag: ${e.message}") }
            finally { busy.value = false }
        }
    }
}
