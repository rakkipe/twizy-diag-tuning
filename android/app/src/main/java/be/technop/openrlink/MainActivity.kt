@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package be.technop.openrlink

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import be.technop.openrlink.renault.TwizyActions
import be.technop.openrlink.ui.AppViewModel
import be.technop.openrlink.ui.theme.OpenRLinkTheme
import be.technop.openrlink.elm.Elm327
import be.technop.openrlink.transport.DemoTransport
import be.technop.openrlink.ev.EvVehicle
import be.technop.openrlink.ui.EvScreen
import be.technop.openrlink.ui.EvInfoScreen
import be.technop.openrlink.ui.EvLiveScreen
import be.technop.openrlink.ui.EvEcuScreen

class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* resultaat wordt in de UI gereflecteerd bij volgende actie */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBtPermissions()
        setContent { OpenRLinkTheme { Root() } }
    }

    private fun requestBtPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        permLauncher.launch(perms)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(vm: AppViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    val isTwizy = vm.vehicle.value.isTwizy
    val tabs = listOf("Verbinden", "OBD", if (isTwizy) "Twizy" else "Accu",
        if (isTwizy) "Tuning" else "Info", "Live", "ECU", if (isTwizy) "Terminal" else "Rapport")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenRLink 1.5") },
                actions = {
                    if (vm.busy.value) CircularProgressIndicator(
                        Modifier.size(20.dp).padding(end = 12.dp), strokeWidth = 2.dp
                    )
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text(
                "${vm.vehicle.value.label}\n${if (vm.demo.value) "DEMO — fictieve gegevens · " else ""}${vm.status.value}",
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (vm.connected.value) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
            )
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                tabs.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> ConnectScreen(vm)
                    1 -> ObdScreen(vm)
                    2 -> if (isTwizy) TwizyScreen(vm) else EvScreen(vm)
                    3 -> if (isTwizy) TuningScreen(vm) else EvInfoScreen(vm)
                    4 -> LiveScreen(vm)
                    5 -> EcuScreen(vm)
                    6 -> if (isTwizy) TerminalScreen(vm) else EvScreen(vm, reportOnly = true)
                }
            }
            LogPane(vm)
        }
    }
}

@Composable
private fun ConnectScreen(vm: AppViewModel) {
    val devices = remember { mutableStateOf(vm.bondedDevices()) }
    var host by remember { mutableStateOf("192.168.4.1") }
    var port by remember { mutableStateOf("35000") }
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {

        SectionTitle("Voertuig")
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = !vm.connected.value && !vm.busy.value) {
                Text(vm.vehicle.value.label)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                EvVehicle.entries.forEach { v ->
                    DropdownMenuItem(text = { Text(v.label) }, onClick = { vm.selectVehicle(v); expanded = false })
                }
            }
        }
        Text(vm.vehicle.value.note, style = MaterialTheme.typography.bodySmall)
        if (vm.connected.value) Text("Verbreek de verbinding om van voertuig te wisselen.", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(12.dp))

        SectionTitle("1) vLinker FS via USB-OTG")
        Text("OTG-kabel tussen telefoon en vLinker. Eerste keer: permissie toestaan, dan nogmaals drukken.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.connectUsb() }, enabled = !vm.connected.value) { Text("Verbind USB") }
            if (vm.connected.value) Button(onClick = { vm.disconnect() }) { Text("Verbreek") }
        }

        Spacer(Modifier.height(14.dp))
        SectionTitle("2) M5StickC WiFi-CAN brug")
        Text("Telefoon op WiFi 'TwizyBridge' (wachtwoord: twizy2012), dan verbinden.",
            style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(host, { host = it }, Modifier.weight(1f), singleLine = true, label = { Text("IP") })
            OutlinedTextField(port, { port = it }, Modifier.width(110.dp), singleLine = true, label = { Text("Poort") })
        }
        Button(onClick = { vm.connectTcp(host.trim(), port.trim().toIntOrNull() ?: 35000) },
            enabled = !vm.connected.value, modifier = Modifier.padding(top = 6.dp)) { Text("Verbind brug") }

        Spacer(Modifier.height(14.dp))
        SectionTitle("3) Overig")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.connectDemo() }, enabled = !vm.connected.value) { Text("Demo-modus") }
            OutlinedButton(onClick = { devices.value = vm.bondedDevices() }) { Text("BT verversen") }
        }
        Text("Bluetooth (alleen voor BT-adapters; jouw vLinker FS is USB):",
            style = MaterialTheme.typography.bodySmall)
        devices.value.forEach { d ->
            ListItem(
                headlineContent = { Text(d.name ?: "(naamloos)") },
                supportingContent = { Text(d.address) },
                trailingContent = {
                    Button(onClick = { vm.connectBluetooth(d) }, enabled = !vm.connected.value) {
                        Text("Verbind")
                    }
                }
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun ObdScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Text("Algemene OBD-II via CAN. Een elektrische wagen ondersteunt deze emissiediensten mogelijk niet; gebruik ook Accu en ECU.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.scanDtcs() }, enabled = vm.connected.value && !vm.busy.value && !vm.liveRunning.value) { Text("Scan DTC's") }
            if (vm.vehicle.value.isTwizy) Button(onClick = { vm.clearDtcs() }) { Text("Wis DTC's") }
            Button(onClick = { vm.readLive() }, enabled = vm.connected.value && !vm.busy.value && !vm.liveRunning.value) { Text("Live data") }
        }
        Spacer(Modifier.height(12.dp))
        vm.obdReadStatus.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        SectionTitle("Opgeslagen DTC's (mode 03)")
        if (vm.dtcStored.isEmpty()) Text("—") else vm.dtcStored.forEach { Text("• $it") }
        Spacer(Modifier.height(8.dp))
        SectionTitle("Pending DTC's (mode 07)")
        if (vm.dtcPending.isEmpty()) Text("—") else vm.dtcPending.forEach { Text("• $it") }
        Spacer(Modifier.height(8.dp))
        SectionTitle("Permanente DTC's (mode 0A)")
        if (vm.dtcPermanent.isEmpty()) Text("—") else vm.dtcPermanent.forEach { Text("• $it") }
        Spacer(Modifier.height(12.dp))
        SectionTitle("Live data (mode 01)")
        vm.live.forEach { Text("${it.label}: ${"%.1f".format(it.value)} ${it.unit}") }
    }
}

@Composable
private fun TwizyScreen(vm: AppViewModel) {
    // De actie-lijst hangt aan een dummy-engine enkel om titels te tonen;
    // execute() in de VM gebruikt de echte verbonden engine.
    val actions = remember { TwizyActions(Elm327(DemoTransport())).actions }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Twizy quick-actions — CAN-ID's zijn STARTPUNTEN, verifieer per ECU.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            items(actions) { a ->
                OutlinedButton(
                    onClick = { vm.runTwizyAction(a) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                ) { Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun LiveScreen(vm: AppViewModel) {
    if (!vm.vehicle.value.isTwizy) { EvLiveScreen(vm); return }
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { vm.toggleLiveMeters() }, enabled = vm.connected.value) {
                Text(if (vm.liveRunning.value) "■ Live stoppen" else "▶ Live starten")
            }
            Text(if (vm.liveRunning.value) "● live" else "○ uit",
                color = if (vm.liveRunning.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium)
        }
        Text("OVMS-meetblok (0x4600/0x4602). Koppel/stroom bewegen alleen in GO tijdens rijden.",
            style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(10.dp))
        if (vm.meters.isEmpty()) Text("—")
        else vm.meters.forEach { (label, value) ->
            ListItem(
                headlineContent = { Text(label) },
                trailingContent = { Text(value, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary) }
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun TuningScreen(vm: AppViewModel) = be.technop.openrlink.ui.ProfileEditorScreen(vm)

@Composable
private fun EcuScreen(vm: AppViewModel) {
    if (!vm.vehicle.value.isTwizy) { EvEcuScreen(vm); return }
    val context = androidx.compose.ui.platform.LocalContext.current
    // check DB-status buiten de UI-thread, éénmalig bij openen
    LaunchedEffect(Unit) { vm.refreshDtcDb() }
    // systeem-bestandskiezer voor de ecu.zip
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val input = context.contentResolver.openInputStream(uri)
            if (input != null) vm.importEcuZip(input)
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("Multi-ECU DTC-scan (Twizy)")
        Text("Leest foutcodes van UCH/BMS/lader/display (read-only, geen writes). " +
            "Codes krijgen klachttekst zodra je je eigen ecu.zip in de app hebt geladen.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.ecuScan() }, enabled = vm.connected.value) { Text("Scan alle ECU's") }

        Spacer(Modifier.height(12.dp))
        SectionTitle("DTC-database (ecu.zip)")
        val dbOk = vm.dtcDbReady.value
        Text(
            if (dbOk) "✓ DTC-database geladen — codes worden vertaald"
            else "ⓘ Nog geen database — kies je ecu.zip om codes te vertalen",
            style = MaterialTheme.typography.bodySmall,
            color = if (dbOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                Text("Kies ecu.zip")
            }
            OutlinedButton(onClick = { vm.refreshDtcDb() }) { Text("Opnieuw checken") }
        }
        Text("Wordt gekopieerd naar:\n${vm.dtcDbPath()}",
            style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)

        Spacer(Modifier.height(12.dp))
        SectionTitle("Resultaat")
        if (vm.ecuScanResults.isEmpty()) Text("—")
        else vm.ecuScanResults.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun TerminalScreen(vm: AppViewModel) {
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("ATZ", "ATI", "0100", "03").forEach { q ->
                AssistChip(onClick = { vm.sendRaw(q) }, label = { Text(q) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("AT / ST / hex-commando") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { if (input.isNotBlank()) { vm.sendRaw(input.trim()); input = "" } }) { Text("Stuur") }
        }
        Spacer(Modifier.height(8.dp))
        Text(vm.terminalOutput.value, fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
    }
}

@Composable
private fun LogPane(vm: AppViewModel) {
    HorizontalDivider()
    Column(Modifier.fillMaxWidth().height(110.dp).padding(horizontal = 12.dp, vertical = 4.dp)
        .verticalScroll(rememberScrollState())) {
        vm.log.forEach { Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
    }
}

@Composable private fun SectionTitle(t: String) =
    Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
