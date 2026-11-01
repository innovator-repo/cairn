package app.cairn.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.cairn.backup.Backup
import java.text.DateFormat
import java.util.Date

private enum class Pending { NONE, CREATE, RESTORE }

@Composable
fun SettingsScreen(vm: CairnViewModel, onBack: () -> Unit) {
    val c = LocalCairnColors.current
    var token by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(Pending.NONE) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) { pickedUri = uri; pending = Pending.CREATE }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { pickedUri = uri; pending = Pending.RESTORE }
    }

    Column(Modifier.fillMaxSize().background(c.canvas).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.ink) }
            Text("Cairn", color = c.inkSecondary, style = MaterialTheme.typography.labelLarge)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Settings", color = c.ink, style = MaterialTheme.typography.headlineMedium)

            Section("GitHub") {
                if (vm.hasToken) {
                    Text("Connected. Repositories, READMEs and issues are indexed on this phone and searchable offline.", color = c.inkSecondary, style = MaterialTheme.typography.bodyMedium)
                    vm.lastSync?.let { Text("Last sync ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}", color = c.inkSecondary, style = MaterialTheme.typography.labelMedium) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(if (vm.busy) "Syncing" else "Sync now", { vm.sync() }, filled = true)
                        Pill("Remove token", { vm.removeToken() })
                    }
                } else {
                    Text("Paste a fine-grained personal access token with read-only access to metadata, contents and issues. It is encrypted with a key in the Android Keystore and never leaves this phone except to call GitHub.", color = c.inkSecondary, style = MaterialTheme.typography.bodyMedium)
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).border(1.dp, c.hairline, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                        if (token.isEmpty()) Text("github_pat_…", color = c.inkSecondary, style = MaterialTheme.typography.bodyMedium)
                        BasicTextField(
                            token, { token = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.ink), cursorBrush = SolidColor(c.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Pill("Save token", { vm.saveToken(token); token = "" }, filled = true)
                }
            }

            if (vm.hasToken) Section("Repository files") {
                Text("Choose which repositories have their text files indexed for search (up to 40 small files each, no vendored or built folders). Takes effect on the next sync.", color = c.inkSecondary, style = MaterialTheme.typography.bodyMedium)
                val repos = vm.repos()
                if (repos.isEmpty()) Text("Sync GitHub to list your repositories.", color = c.inkSecondary, style = MaterialTheme.typography.labelMedium)
                for (r in repos) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    KindIcon(r.kind, c.inkSecondary)
                    Text(r.path, Modifier.weight(1f).padding(horizontal = 10.dp), color = c.ink, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Switch(checked = r.path !in vm.skipFiles, onCheckedChange = { vm.setFilesIndexed(r.path, it) })
                }
            }

            Section("Backup") {
                Text("One encrypted file with all your notes and the GitHub index. Restore it on a new phone with the same passphrase. The token is not included.", color = c.inkSecondary, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("Create backup", { create.launch("cairn-backup.cairn") }, filled = true)
                    Pill("Restore", { restore.launch(arrayOf("*/*")) })
                }
            }
            Section("Developer") {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Show frame times on the map", Modifier.weight(1f), color = c.ink, style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = vm.showFrameTimes, onCheckedChange = { vm.setFrameTimes(it) })
                }
            }
            if (vm.message.isNotBlank()) Text(vm.message, color = c.ink, style = MaterialTheme.typography.bodyMedium)
            Text("Cairn 0.2.0 · notes stay on this phone", color = c.inkSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 24.dp))
        }
    }

    if (pending != Pending.NONE) PassphraseDialog(
        confirm = pending == Pending.CREATE,
        onCancel = { pending = Pending.NONE; pickedUri = null },
        onOk = { pass ->
            val uri = pickedUri
            if (uri != null) {
                if (pending == Pending.CREATE) vm.createBackup(uri, pass) else vm.restoreBackup(uri, pass)
            }
            pending = Pending.NONE; pickedUri = null
        },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val c = LocalCairnColors.current
    Column(
        Modifier.fillMaxWidth().background(c.grouped, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = c.ink, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun PassphraseDialog(confirm: Boolean, onCancel: () -> Unit, onOk: (CharArray) -> Unit) {
    val c = LocalCairnColors.current
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val ok = a.length >= Backup.MIN_PASSPHRASE && (!confirm || a == b)
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = c.grouped,
        title = { Text(if (confirm) "Choose a passphrase" else "Enter the passphrase", color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (confirm) Text("At least ${Backup.MIN_PASSPHRASE} characters. There is no way to recover a lost passphrase.", color = c.inkSecondary, style = MaterialTheme.typography.bodyMedium)
                PassField(a, { a = it }, "Passphrase")
                if (confirm) PassField(b, { b = it }, "Repeat passphrase")
            }
        },
        confirmButton = { TextButton(onClick = { onOk(a.toCharArray()) }, enabled = ok) { Text(if (confirm) "Create" else "Restore") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun PassField(value: String, onChange: (String) -> Unit, hint: String) {
    val c = LocalCairnColors.current
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).border(1.dp, c.hairline, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(hint, color = c.inkSecondary, style = MaterialTheme.typography.bodyMedium)
        BasicTextField(
            value, onChange, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.ink), cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
