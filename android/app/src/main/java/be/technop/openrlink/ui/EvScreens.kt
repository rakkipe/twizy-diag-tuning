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

@Composable
fun EvInfoScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Text(vm.vehicle.value.label, style = MaterialTheme.typography.titleLarge)
        Text(vm.vehicle.value.note)
        Spacer(Modifier.height(12.dp))
        Text("Accu: lees een momentopname of alle 96 celspanningen. Live: ververst de kernwaarden om de tien seconden na de vorige uitlezing. ECU: leest foutcodes van de opgenomen modules.")
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
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Text("ECU-diagnose — ${vm.vehicle.value.label}", style = MaterialTheme.typography.titleLarge)
        Text("Leest UDS-foutcodes van de hieronder opgenomen modules. Dit is geen volledige scan van alle systemen van de wagen.")
        vm.vehicle.value.scanTargets.forEach { Text("• ${it.name}") }
        Text("Een module zonder passend antwoord wordt als niet uitgelezen gemeld. Ruwe codes behouden de ECU-herkomst; een Twizy-foutomschrijving wordt hier niet toegepast.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { vm.ecuScan() }, enabled = vm.connected.value && !vm.busy.value && !vm.liveRunning.value) { Text("Lees opgenomen ECU's") }
        vm.ecuScanResults.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    }
}
