package app.cairn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cairn.data.Entry
import app.cairn.data.Kind
import app.cairn.graph.ViewMode
import app.cairn.map.MapCanvas
import app.cairn.map.MapColors
import app.cairn.map.MapController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: CairnViewModel,
    onEditNote: (Entry?) -> Unit,
    onSettings: () -> Unit,
) {
    val c = LocalCairnColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val map = remember { MapController(scope) }
    map.animate = remember {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val graph = vm.graph
    LaunchedEffect(graph) { map.loadGraph(graph) }

    var query by remember { mutableStateOf("") }
    LaunchedEffect(query, graph) {
        if (query.isBlank()) { map.highlight(emptySet()); return@LaunchedEffect }
        delay(250)
        val hits = vm.search(query)
        val index = graph.entries.withIndex().associate { (i, e) -> e.id to i }
        map.highlight(hits.mapNotNull { index[it.entry.id] }.toSet())
        hits.firstOrNull()?.let { best -> index[best.entry.id]?.let { map.select(it); map.flyTo(it) } }
    }

    val sheet = rememberBottomSheetScaffoldState(rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = true))
    val peek = 170.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    BottomSheetScaffold(
        scaffoldState = sheet,
        sheetPeekHeight = peek,
        containerColor = c.canvas,
        sheetContainerColor = c.grouped,
        sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        sheetTonalElevation = 0.dp,
        sheetShadowElevation = 0.dp,
        sheetContent = {
            Drawer(
                vm = vm, map = map, expanded = sheet.bottomSheetState.targetValue == SheetValue.Expanded,
                onNewNote = { onEditNote(null) }, onSettings = onSettings,
                onPick = { i ->
                    map.select(i); map.flyTo(i)
                    scope.launch { sheet.bottomSheetState.partialExpand() }
                },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(bottom = inner.calculateBottomPadding()).statusBarsPadding()) {
            Header(map.view, onSettings)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(ViewMode.entries.toList()) { v -> Pill(v.label, { map.showView(v) }, selected = v == map.view) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                MapCanvas(
                    map, MapColors(c.grouped, c.hairline, c.ink, c.inkSecondary),
                    onTapNode = { map.select(it) }, onTapEmpty = { map.select(-1) },
                )
                SearchField(query, { query = it }, map.hits.size, Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 4.dp))
                Column(Modifier.align(Alignment.BottomCenter)) {
                    val sel = map.selected
                    if (sel in 0 until graph.size) {
                        NodeCard(graph.entries[sel], graph.degree[sel], graph.areas[graph.areaIndex[sel]].color,
                            onOpen = { openEntry(ctx, graph.entries[sel], onEditNote) },
                            onCopy = { copyPath(ctx, graph.entries[sel].path) },
                            onFly = { map.flyTo(sel) },
                            onClose = { map.select(-1) })
                    }
                    Legend(map, graph)
                }
            }
        }
    }
}

@Composable
private fun Header(view: ViewMode, onSettings: () -> Unit) {
    val c = LocalCairnColors.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Emblem()
        Text("Cairn", Modifier.padding(start = 10.dp), color = c.ink, style = MaterialTheme.typography.titleMedium)
        Text(view.label.lowercase(), Modifier.padding(start = 10.dp), color = c.inkSecondary, style = MaterialTheme.typography.labelMedium)
        Box(Modifier.weight(1f))
        IconButton(onClick = onSettings, Modifier.size(48.dp)) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = c.inkSecondary)
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, hitCount: Int, modifier: Modifier) {
    val c = LocalCairnColors.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .background(c.grouped, RoundedCornerShape(14.dp))
            .border(1.dp, c.hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = c.inkSecondary, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            if (query.isEmpty()) Text("Search titles, paths and notes", color = c.inkSecondary, style = MaterialTheme.typography.bodyMedium)
            BasicTextField(
                value = query, onValueChange = onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.ink),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search" },
            )
        }
        if (query.isNotEmpty()) {
            Text("$hitCount", color = c.inkSecondary, style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { onChange("") }, Modifier.size(40.dp)) { Icon(Icons.Filled.Close, "Clear search", tint = c.inkSecondary) }
        }
    }
}

@Composable
private fun Legend(map: MapController, graph: app.cairn.graph.Graph) {
    val c = LocalCairnColors.current
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(graph.areas.size) { i ->
                val a = graph.areas[i]
                Pill(a.name, { map.isolate(i) }, selected = map.isolated == i, dot = Color(a.color))
            }
        }
        Text(
            "● note   ■ repo   ▪ file   ▲ issue   ◆ link",
            Modifier.padding(horizontal = 16.dp), color = c.inkSecondary, style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun NodeCard(
    e: Entry, links: Int, areaColor: Long,
    onOpen: () -> Unit, onCopy: () -> Unit, onFly: () -> Unit, onClose: () -> Unit,
) {
    val c = LocalCairnColors.current
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp), color = c.grouped, border = BorderStroke(1.dp, c.hairline),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(Color(areaColor), CircleShape))
                Text(e.title, Modifier.weight(1f).padding(horizontal = 8.dp), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(e.kind.label, color = c.inkSecondary, style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = onClose, Modifier.size(40.dp)) { Icon(Icons.Filled.Close, "Close", tint = c.inkSecondary) }
            }
            Text(e.path, color = c.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
            val snippet = e.text.take(110)
            Text(
                if (snippet.isBlank()) "$links links" else "$snippet · $links links",
                Modifier.padding(top = 4.dp), color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("Open", onOpen, filled = true)
                Pill("Copy path", onCopy)
                Pill("Fly to", onFly)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Drawer(
    vm: CairnViewModel, map: MapController, expanded: Boolean,
    onNewNote: () -> Unit, onSettings: () -> Unit, onPick: (Int) -> Unit,
) {
    val c = LocalCairnColors.current
    val g = vm.graph
    val summary = if (vm.busy && vm.message.isNotBlank()) vm.message else "${g.size} items · ${g.links.size} links"
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Brain", color = c.ink, style = MaterialTheme.typography.titleLarge)
            Box(Modifier.weight(1f))
            Text(summary, color = c.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(2f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("New note", onNewNote, filled = true)
            if (vm.hasToken) Pill(if (vm.busy) "Syncing" else "Sync GitHub", { vm.sync() })
            Pill("Backup", onSettings)
        }
        if (vm.message.isNotBlank() && !vm.busy) {
            Text(vm.message, color = c.inkSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
        }
        val order = remember(g) { g.entries.indices.sortedByDescending { g.entries[it].updatedAt } }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            items(order) { i ->
                val e = g.entries[i]
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onPick(i) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(9.dp).background(Color(g.areas[g.areaIndex[i]].color), CircleShape))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(e.title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Text(e.path, color = c.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(e.kind.label, color = c.inkSecondary, style = MaterialTheme.typography.labelMedium)
                }
                Divider(color = c.hairline)
            }
        }
    }
}

private fun openEntry(ctx: Context, e: Entry, edit: (Entry?) -> Unit) {
    if (e.kind == Kind.REPO || e.kind == Kind.ISSUE) {
        val url = e.url
        if (url != null) {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { Toast.makeText(ctx, "No app can open this link", Toast.LENGTH_SHORT).show() }
        }
    } else edit(e)
}

private fun copyPath(ctx: Context, path: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("path", path))
    Toast.makeText(ctx, "Path copied", Toast.LENGTH_SHORT).show()
}
