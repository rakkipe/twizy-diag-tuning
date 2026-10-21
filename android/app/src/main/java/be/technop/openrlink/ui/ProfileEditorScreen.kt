package be.technop.openrlink.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import be.technop.openrlink.sevcon.ProfileRules
import be.technop.openrlink.sevcon.TuningProfile
import be.technop.openrlink.sevcon.TuningProfiles
import kotlin.math.roundToInt

@Composable
fun ProfileEditorScreen(vm: AppViewModel) {
    var confirm by remember { mutableStateOf<TuningProfile?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }

    fun edit(p: TuningProfile, copy: Boolean) {
        vm.profileMessage.value = ""
        vm.editingProfileName.value = if (copy) null else p.name
        var name = "Mijn ${p.name}"
        var suffix = 2
        while (vm.tuningProfileNames().any { it.equals(name, true) }) name = "Mijn ${p.name} ${suffix++}"
        vm.profileDraft.value = if (copy) p.copy(name = name) else p
    }

    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Text("Jouw rijprofielen", style = MaterialTheme.typography.titleLarge)
        Text("Kies een basis of maak je eigen profiel. Bewaren kan ook zonder auto.",
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { edit(TuningProfiles.LIST.first(), true) }, enabled = !vm.busy.value,
            modifier = Modifier.fillMaxWidth()) { Text("+ Nieuw profiel") }
        if (vm.profileMessage.value.isNotBlank()) {
            Text(vm.profileMessage.value, Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary)
        }
        Text("Standaardprofielen", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))

        @Composable
        fun ProfileCard(p: TuningProfile, custom: Boolean) {
            ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    if (p.name == "DRAGRACE") Text("Sprintbasis voor circuit · nog niet op de auto getest",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                    Text(ProfileRules.summary(p), Modifier.padding(vertical = 7.dp),
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { confirm = p }, enabled = vm.connected.value && !vm.busy.value,
                            modifier = Modifier.weight(1f)) { Text("Toepassen") }
                        OutlinedButton(onClick = { edit(p, !custom) }, enabled = !vm.busy.value,
                            modifier = Modifier.weight(1f)) { Text(if (custom) "Bewerk" else "Eigen kopie") }
                    }
                    if (custom) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { edit(p, true) }, enabled = !vm.busy.value) { Text("Kopie maken") }
                        TextButton(onClick = { deleting = p.name }, enabled = !vm.busy.value) { Text("Verwijderen") }
                    }
                }
            }
        }

        TuningProfiles.LIST.forEach { ProfileCard(it, false) }
        Text("Eigen profielen", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        if (vm.customProfiles.isEmpty()) Text("Nog geen eigen profielen. Begin met een kopie van je favoriete basis.",
            style = MaterialTheme.typography.bodySmall)
        vm.customProfiles.forEach { ProfileCard(it, true) }
        Text("Toepassen schrijft naar de controller en vraagt apart om bevestiging.",
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 10.dp))
    }

    vm.profileDraft.value?.let { draft ->
        ProfileEditorDialog(draft, vm.profileMessage.value, vm.busy.value,
            change = { vm.profileDraft.value = it },
            close = { if (!vm.busy.value) vm.profileDraft.value = null },
            save = { vm.saveProfile(draft, vm.editingProfileName.value) { vm.profileDraft.value = null } })
    }

    confirm?.let { p ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text("${p.name} toepassen?") },
            text = { Column {
                Text(ProfileRules.summary(p))
                Spacer(Modifier.height(12.dp))
                Text("Dit schrijft naar de SEVCON. Zet de auto stil in N, niet in GO, met contact aan.")
            } },
            confirmButton = { TextButton(onClick = { vm.applyTuning(p.name); confirm = null },
                enabled = vm.connected.value && !vm.busy.value) { Text("Ja, schrijven") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Annuleren") } })
    }

    deleting?.let { name ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Profiel verwijderen?") },
            text = { Text("$name wordt van deze telefoon verwijderd. De auto wordt niet gewijzigd.") },
            confirmButton = { TextButton(onClick = { vm.deleteProfile(name); deleting = null },
                enabled = !vm.busy.value) { Text("Verwijderen") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Behouden") } })
    }
}

@Composable
private fun ProfileEditorDialog(p: TuningProfile, message: String, busy: Boolean,
    change: (TuningProfile) -> Unit, close: () -> Unit, save: () -> Unit) {
    Dialog(onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false,
            dismissOnBackPress = !busy, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp)) {
                Text("Profiel-editor", style = MaterialTheme.typography.headlineSmall)
                Text("Bewaren wijzigt alleen je profiel op de telefoon.", style = MaterialTheme.typography.bodySmall)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(p.name, { change(p.copy(name = it.take(40))) },
                        label = { Text("Naam van je profiel") }, singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                    Text("Snelheid", style = MaterialTheme.typography.titleMedium)
                    ProfileNumber("Maximumsnelheid", p.speed, 6..120, "km/u", busy,
                        factory = 80, update = { change(p.copy(speed = it)) })
                    ProfileNumber("Snelheidsmelding", p.warn, 6..130, "km/u", busy,
                        factory = 89, update = { change(p.copy(warn = it)) })
                    Text("Vermogen en koppel", style = MaterialTheme.typography.titleMedium)
                    ProfileNumber("Koppel", p.torque, 10..150, "%", busy) { change(p.copy(torque = it)) }
                    ProfileNumber("Vermogen laag toerental", p.powerLow, 10..140, "%", busy) { change(p.copy(powerLow = it)) }
                    ProfileNumber("Vermogen hoog toerental", p.powerHigh, 10..140, "%", busy) { change(p.copy(powerHigh = it)) }
                    ProfileNumber("Stroomlimiet", p.current, 10..120, "%", busy) { change(p.copy(current = it)) }
                    ProfileNumber("Beschikbaar aandrijfkoppel", p.drive, 0..100, "%", busy) { change(p.copy(drive = it)) }
                    Text("Reactie bij optrekken", style = MaterialTheme.typography.titleMedium)
                    ProfileNumber("Starthelling", p.rampStart, 1..100, "‰", busy) { change(p.copy(rampStart = it)) }
                    ProfileNumber("Opbouw aandrijfkoppel", p.rampAccel, 1..100, "%", busy) { change(p.copy(rampAccel = it)) }
                    Text("Regeneratief remmen", style = MaterialTheme.typography.titleMedium)
                    ProfileNumber("Bij gas loslaten", p.neutral, 0..100, "%", busy) { change(p.copy(neutral = it)) }
                    ProfileNumber("Bij remmen", p.brake, 0..100, "%", busy) { change(p.copy(brake = it)) }
                    val errors = ProfileRules.errors(p)
                    errors.forEach { Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall) }
                    if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp))
                    Text("Dit zijn appgrenzen, geen garantie voor jouw voertuig. Pas een nieuw profiel pas toe na controle.",
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 12.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = close, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Annuleren") }
                    Button(onClick = save, enabled = !busy && ProfileRules.errors(p).isEmpty(),
                        modifier = Modifier.weight(1f)) { Text(if (busy) "Bewaren…" else "Bewaren") }
                }
            }
        }
    }
}

@Composable
private fun ProfileNumber(label: String, value: Int, range: IntRange, unit: String,
    busy: Boolean, factory: Int? = null, update: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text("$label: ${if (value == -1) "fabriekswaarde" else "$value $unit"}",
            style = MaterialTheme.typography.bodyMedium)
        if (factory != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Gebruik fabriekswaarde", style = MaterialTheme.typography.bodySmall)
            Switch(checked = value == -1, enabled = !busy,
                onCheckedChange = { update(if (it) -1 else factory) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { update((value - 1).coerceIn(range)) },
                enabled = !busy && value > range.first) { Text("−") }
            Slider(value = (if (value == -1) factory ?: range.first else value).toFloat(),
                onValueChange = { update(it.roundToInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = range.last - range.first - 1,
                enabled = !busy && value != -1, modifier = Modifier.weight(1f))
            TextButton(onClick = { update((value + 1).coerceIn(range)) },
                enabled = !busy && value != -1 && value < range.last) { Text("+") }
        }
    }
}
