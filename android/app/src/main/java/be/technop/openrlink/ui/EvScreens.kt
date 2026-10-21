package be.technop.openrlink.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import be.technop.openrlink.ev.EcuModules

@Composable
fun EvInfoScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Text(vm.vehicle.value.label, style = MaterialTheme.typography.titleLarge)
        Text(vm.vehicle.value.note)
        Spacer(Modifier.height(12.dp))
        Text("Accu: lees een momentopname of alle 96 celspanningen. Live: ververst de kernwaarden om de tien seconden na de vorige uitlezing. ECU: scant de bekende modules, aangevuld met moduleadressen uit je eigen Zoe-database.")
        Spacer(Modifier.height(12.dp))
        Text("De profielen zijn gebaseerd op gepubliceerde OVMS/CanZE-protocolgegevens. Deze OpenRLink-uitbreiding is nog niet op een echte Zoe of Leaf gevalideerd.")
        Spacer(Modifier.height(12.dp))
        Text("Dit voertuigprofiel kan alleen uitlezen. Voor deze wagen zijn tuning, foutwissen en terminalcommando's uitgeschakeld.")
        Spacer(Modifier.height(12.dp))
        Text("Ontbrekende waarden blijven leeg. SOH is alleen zichtbaar als de gekozen ECU-indeling die rechtstreeks levert. Hx bij een Leaf is geen SOH. De celvergelijking gebruikt 96 geldige metingen uit dezelfde uitlezing.")
    }
}

@Composable
fun EvScreen(vm: AppViewModel, reportOnly: Boolean = false) {
    val context = LocalContext.current
    var exportText by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) try {
            val stream = context.contentResolver.openOutputStream(uri) ?: error("Bestand kan niet worden geopend")
            stream.bufferedWriter().use { it.write(exportText) }
            vm.evMessage.value = "Rapport opgeslagen."
        } catch (ex: Exception) { vm.evMessage.value = "Rapport opslaan mislukt: ${ex.message}" }
    }
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Text(if (reportOnly) "Uitleesrapport" else "Batterij — ${vm.vehicle.value.label}", style = MaterialTheme.typography.titleLarge)
        Text("Uitleesprofiel in ontwikkeling; nog niet fysiek gevalideerd.", style = MaterialTheme.typography.bodySmall)
        if (!reportOnly) {
            Button(onClick = { vm.readEv(false) }, enabled = vm.connected.value && !vm.busy.value && !vm.liveRunning.value) { Text("Accu uitlezen") }
            OutlinedButton(onClick = { vm.readEv(true) }, enabled = vm.connected.value && !vm.busy.value && !vm.liveRunning.value) { Text("Accu + 96 celspanningen") }
            Text("ZE50 leest de cellen apart; dit duurt langer. Stop Live voordat je een volledige scan doet.", style = MaterialTheme.typography.labelSmall)
        }
        Text(vm.evProgress.value, style = MaterialTheme.typography.bodySmall)
        Text(vm.evMessage.value, style = MaterialTheme.typography.bodySmall)
        val snapshot = vm.evSnapshot.value
        if (snapshot != null) {
            OutlinedButton(onClick = {
                exportText = (if (vm.demo.value) "DEMO — FICTIEVE GEGEVENS\n\n" else "") + snapshot.report()
                export.launch("OpenRLink-${snapshot.vehicle.name}-${snapshot.timestamp}.txt")
            }) { Text("Rapport opslaan") }
        }
        EvValues(vm)
        if (reportOnly) snapshot?.details?.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun EvValues(vm: AppViewModel) {
    val snapshot = vm.evSnapshot.value ?: return
    Text("Gemeten: ${DateFormat.getDateTimeInstance().format(Date(snapshot.timestamp))}", style = MaterialTheme.typography.labelSmall)
    if (vm.demo.value) Text("DEMO — fictieve meetwaarden", color = MaterialTheme.colorScheme.error)
    snapshot.metrics.forEach { m ->
        ListItem(headlineContent = { Text(m.label) }, trailingContent = { Text("${String.format(Locale.getDefault(), "%.2f", m.value)} ${m.unit}") })
    }
    if (snapshot.cells.isNotEmpty()) {
        Text("Celspanningen: ${snapshot.cells.size}/96", style = MaterialTheme.typography.titleMedium)
        val delta = snapshot.cellDeltaMv
        if (delta != null) Text("Min: %.3f V · Max: %.3f V · Verschil: %.1f mV".format(snapshot.cells.values.min(), snapshot.cells.values.max(), delta))
        else Text("Onvolledige cellijst — geen totaal celverschil berekend.")
        var showCells by remember(snapshot.timestamp) { mutableStateOf(false) }
        TextButton(onClick = { showCells = !showCells }) { Text(if (showCells) "Celspanningen verbergen" else "Alle celspanningen tonen") }
        if (showCells) snapshot.cells.toSortedMap().forEach { (i, v) -> Text("Cel %02d: %.3f V".format(i, v), fontFamily = FontFamily.Monospace) }
    }
}

@Composable
fun EvLiveScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Text("Live batterijgegevens", style = MaterialTheme.typography.titleLarge)
        Button(onClick = { vm.toggleLiveMeters() }, enabled = vm.connected.value && !vm.busy.value) {
            Text(if (vm.liveRunning.value) "Live stoppen" else "Live starten")
        }
        Text("Elke tien seconden na de vorige uitlezing. Celspanningen lees je apart via Accu.", style = MaterialTheme.typography.bodySmall)
        Text(vm.evMessage.value)
        EvValues(vm)
    }
}

@Composable
fun EvEcuScreen(vm: AppViewModel) {
    val context = LocalContext.current
    var selected by remember(vm.vehicle.value) { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf("") }
    LaunchedEffect(vm.vehicle.value) { vm.refreshDtcDb() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val stream = context.contentResolver.openInputStream(uri) ?: error("Bestand niet leesbaar")
            vm.importEcuZip(stream)
        } catch (ex: Exception) { vm.ecuDatabaseMessage.value = "Bestand openen mislukt: ${ex.message}" }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) try {
            val output = context.contentResolver.openOutputStream(uri) ?: error("Bestand niet schrijfbaar")
            output.bufferedWriter().use { it.write(report) }
        } catch (ex: Exception) { vm.ecuScanProgress.value = "Rapport opslaan mislukt: ${ex.message}" }
    }
    val modules = vm.ecuModules.toList().ifEmpty { EcuModules.builtIn(vm.vehicle.value) }
    val idle = !vm.busy.value && !vm.liveRunning.value && !vm.ecuDatabaseLoading.value
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Text("ECU-diagnose — ${vm.vehicle.value.label}", style = MaterialTheme.typography.titleLarge)
        Text("${modules.size} bekende moduleadressen. De werkelijk gemonteerde en bereikbare modules verschillen per uitvoering en CAN-bus.", style = MaterialTheme.typography.bodySmall)
        Text(if (vm.ecuDatabaseLoading.value) "ecu.zip laden…" else vm.ecuDatabaseMessage.value, style = MaterialTheme.typography.bodySmall)
        if (vm.ecuDatabaseLoading.value) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row {
            OutlinedButton(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = idle) { Text("Kies ecu.zip") }
            TextButton(onClick = { vm.refreshDtcDb(true) }, enabled = idle) { Text("Verversen") }
        }
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = idle) { Text(selected?.let { id -> modules.firstOrNull { it.id == id }?.target?.name } ?: "Alle modules") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("Alle modules") }, onClick = { selected = null; expanded = false })
                modules.forEach { module -> DropdownMenuItem(text = { Text("${module.target.name} (${module.id})") }, onClick = { selected = module.id; expanded = false }) }
            }
        }
        Row {
            Checkbox(checked = vm.identifyEcus.value, onCheckedChange = { vm.identifyEcus.value = it }, enabled = idle)
            Text("Identificatie en software meelezen", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = { vm.ecuScan(selected) }, enabled = vm.connected.value && idle) { Text(if (selected == null) "Scan alle modules" else "Lees deze module") }
        if (vm.ecuScanning.value) OutlinedButton(onClick = { vm.stopEcuScan() }) { Text("Scan stoppen") }
        Text(vm.ecuScanProgress.value, style = MaterialTheme.typography.bodyMedium)
        if (vm.moduleScans.isNotEmpty()) OutlinedButton(onClick = { report = vm.ecuReport(); export.launch("OpenRLink-ECU-${vm.vehicle.value.name}.txt") }) { Text("ECU-rapport opslaan") }
        Text("Geen antwoord is geen foutloze diagnose. Omschrijvingen uit ecu.zip verschijnen alleen na een exacte ECU-identificatiematch. Leaf gebruikt de gepubliceerde modulelijst, geen Renault-omschrijvingen.", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(10.dp))
        if (vm.moduleScans.isEmpty()) modules.forEach { module ->
            Text("• ${module.target.name} · ${module.id}", style = MaterialTheme.typography.bodySmall)
        }
        vm.moduleScans.forEach { result ->
            HorizontalDivider()
            result.lines().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            var rawShown by remember(result) { mutableStateOf(false) }
            TextButton(onClick = { rawShown = !rawShown }) { Text(if (rawShown) "Ruwe antwoorden verbergen" else "Ruwe antwoorden tonen") }
            if (rawShown) result.raw.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
        }
    }
}
