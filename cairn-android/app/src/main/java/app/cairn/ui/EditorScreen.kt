package app.cairn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import app.cairn.data.Entry

/** Write or edit one markdown note. Reached from the New note chip or a node card's Open. */
@Composable
fun EditorScreen(vm: CairnViewModel, entry: Entry?, onDone: () -> Unit) {
    val c = LocalCairnColors.current
    var title by remember { mutableStateOf(entry?.title ?: "") }
    var area by remember { mutableStateOf(entry?.area ?: vm.areaNames().firstOrNull() ?: "Life") }
    var body by remember { mutableStateOf(entry?.let { vm.noteBody(it) } ?: "") }
    Column(Modifier.fillMaxSize().background(c.canvas).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone, Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.ink) }
            Text("Cairn", color = c.inkSecondary, style = MaterialTheme.typography.labelLarge)
            Box(Modifier.weight(1f))
            if (entry != null) Pill("Delete", { vm.deleteNote(entry); onDone() })
            Pill("Save", {
                if (title.isNotBlank()) { vm.saveNote(area, title, body, entry); onDone() }
            }, filled = true)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Field(title, { title = it }, "Title", MaterialTheme.typography.titleLarge)
            Text("Area", Modifier.padding(top = 12.dp), color = c.inkSecondary, style = MaterialTheme.typography.labelMedium)
            Field(area, { area = it }, "Area", MaterialTheme.typography.bodyLarge)
            val others = vm.areaNames()
            if (others.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(others) { a -> Pill(a, { area = a }, selected = a == area) }
            }
            Text("Note (markdown, [[Title]] links to another note)", Modifier.padding(top = 12.dp), color = c.inkSecondary, style = MaterialTheme.typography.labelMedium)
            Field(body, { body = it }, "Write here", MaterialTheme.typography.bodyLarge, singleLine = false, minHeight = 240)
        }
    }
}

@Composable
private fun Field(
    value: String, onChange: (String) -> Unit, hint: String, style: androidx.compose.ui.text.TextStyle,
    singleLine: Boolean = true, minHeight: Int = 48,
) {
    val c = LocalCairnColors.current
    Box(Modifier.fillMaxWidth().heightIn(min = minHeight.dp).padding(vertical = 8.dp)) {
        if (value.isEmpty()) Text(hint, color = c.inkSecondary, style = style)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = singleLine,
            textStyle = style.copy(color = c.ink), cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
