package app.cairn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cairn.data.Entry
import app.cairn.data.Kind
import app.cairn.graph.Graph
import app.cairn.graph.ViewMode
import app.cairn.map.MapCanvas
import app.cairn.map.MapColors
import app.cairn.map.MapPalette
import app.cairn.map.MapController
import app.cairn.search.TextHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
    map.animate = remember { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
    map.debug = vm.showFrameTimes
    val graph = vm.graph
    LaunchedEffect(graph) { map.loadGraph(graph) }
    val idOf = remember(graph) { graph.entries.withIndex().associate { it.value.id to it.index } }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<TextHit>>(emptyList()) }
    val sheet = rememberSheetState()
    LaunchedEffect(query, graph, vm.hasToken) {
        if (query.isBlank()) { results = emptyList(); map.highlight(emptySet()); return@LaunchedEffect }
        delay(250)
        val hits = withContext(Dispatchers.Default) { vm.search(query) }
        results = hits
        map.highlight(hits.mapNotNull { idOf[it.doc.entryId] }.toSet())
        hits.firstOrNull()?.let { best -> idOf[best.doc.entryId]?.let { map.select(it); map.flyTo(it) } }
    }
    map.paused = sheet.expanded
    BackHandler(enabled = sheet.expanded) { sheet.collapse() }

    val peek = 170.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val pick: (Int) -> Unit = { i -> map.select(i); map.flyTo(i); sheet.collapse() }

    Box(Modifier.fillMaxSize().background(c.canvas)) {
        Column(Modifier.fillMaxSize().padding(bottom = peek).statusBarsPadding()) {
            Header(map.view, onSettings)
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ViewMode.entries.toList()) { v -> Pill(v.label, { map.showView(v) }, selected = v == map.view) }
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(MapPalette.BG)) {
                MapCanvas(
                    map, MapColors(MapPalette.DISC, MapPalette.LINE, MapPalette.CREAM, MapPalette.SOFT, MapPalette.MUTE),
                    onTapNode = { map.select(it) }, onTapEmpty = { map.select(-1) },
                )
                SearchField(query, { query = it }, results.size, Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 4.dp))
                Column(Modifier.align(Alignment.BottomCenter)) {
                    val sel = map.selected
                    if (sel in 0 until graph.size) {
                        NodeCard(graph.entries[sel], graph.degree[sel], Color(graph.areas[graph.areaIndex[sel]].color),
                            onOpen = { openEntry(ctx, graph.entries[sel], onEditNote) },
                            onCopy = { copyPath(ctx, graph.entries[sel].path) },
                            onFly = { map.flyTo(sel) },
                            onClose = { map.select(-1) })
                    }
                    Legend(map, graph)
                }
            }
        }
        PullUpSheet(
            state = sheet, peek = peek, containerColor = c.grouped,
            header = { SheetHeader(vm, query.isNotBlank(), results.size, onNewNote = { onEditNote(null) }, onSettings = onSettings, onToggle = { if (sheet.expanded) sheet.collapse() else sheet.expand() }) },
            body = {
                val order = remember(graph) { graph.entries.indices.sortedByDescending { graph.entries[it].updatedAt } }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().testTag("sheetList"),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = navBottom + 24.dp),
                ) {
                    if (query.isNotBlank()) {
                        if (results.isEmpty()) item { Text("No matches", Modifier.padding(vertical = 16.dp), color = c.inkSecondary, style = MaterialTheme.typography.bodyMedium) }
                        itemsIndexed(results, key = { _, h -> h.doc.id }) { _, hit ->
                            val node = idOf[hit.doc.entryId]
                            ResultRow(hit, node?.let { Color(graph.areas[graph.areaIndex[it]].color) } ?: c.inkSecondary) { node?.let(pick) }
                            Divider(color = c.hairline)
                        }
                    } else {
                        items(order, key = { graph.entries[it].id }) { i ->
                            val e = graph.entries[i]
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { pick(i) }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                KindIcon(e.kind, Color(graph.areas[graph.areaIndex[i]].color))
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
            },
        )
    }
}

@Composable
private fun SheetHeader(
    vm: CairnViewModel, searching: Boolean, resultCount: Int,
    onNewNote: () -> Unit, onSettings: () -> Unit, onToggle: () -> Unit,
) {
    val c = LocalCairnColors.current
    val g = vm.graph
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Box(Modifier.fillMaxWidth().height(24.dp).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
            Box(Modifier.width(36.dp).height(4.dp).background(c.inkSecondary.copy(alpha = .5f), RoundedCornerShape(2.dp)))
        }
        val summary = when {
            vm.busy && vm.message.isNotBlank() -> vm.message
            searching -> "$resultCount results"
            else -> "${g.size} items · ${g.links.size} links"
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(if (searching) "Results" else "Brain", color = c.ink, style = MaterialTheme.typography.titleLarge)
            Box(Modifier.weight(1f))
            Text(summary, color = c.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(2f), textAlign = TextAlign.End)
        }
        if (!searching) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("New note", onNewNote, filled = true)
                if (vm.hasToken) Pill(if (vm.busy) "Syncing" else "Sync GitHub", { vm.sync() })
                Pill("Backup", onSettings)
            }
        }
        Box(Modifier.height(4.dp))
    }
}

@Composable
private fun ResultRow(hit: TextHit, color: Color, onClick: () -> Unit) {
    val c = LocalCairnColors.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindIcon(hit.doc.kind, color)
            Text(hit.doc.title, Modifier.weight(1f).padding(start = 10.dp), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        }
        Text(hit.doc.path, Modifier.padding(start = 26.dp), color = c.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        Text(highlighted(hit.snippet, hit.marks, c.accent, c.inkSecondary), Modifier.padding(start = 26.dp, top = 2.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The snippet with each matching range bold and in the accent colour. */
fun highlighted(text: String, marks: List<IntRange>, accent: Color, base: Color): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (m in marks) {
        val s = m.first.coerceIn(0, text.length); val e = (m.last + 1).coerceIn(s, text.length)
        if (s > at) withStyle(SpanStyle(color = base)) { append(text.substring(at, s)) }
        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append(text.substring(s, e)) }
        at = e
    }
    if (at < text.length) withStyle(SpanStyle(color = base)) { append(text.substring(at)) }
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
            if (query.isEmpty()) Text("Search notes, repos, issues and files", color = c.inkSecondary, style = MaterialTheme.typography.bodyMedium)
            BasicTextField(
                value = query, onValueChange = onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.ink),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().testTag("search").semantics { contentDescription = "Search" },
            )
        }
        if (query.isNotEmpty()) {
            Text("$hitCount", color = c.inkSecondary, style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { onChange("") }, Modifier.size(40.dp)) { Icon(Icons.Filled.Close, "Clear search", tint = c.inkSecondary) }
        }
    }
}

@Composable
private fun Legend(map: MapController, graph: Graph) {
    val c = LocalCairnColors.current
    Column(Modifier.fillMaxWidth().background(c.canvas.copy(alpha = 0.88f)).padding(bottom = 4.dp)) {
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(graph.areas.size) { i ->
                val a = graph.areas[i]
                Pill(a.name, { map.isolate(i) }, selected = map.isolated == i, swatch = Color(a.color))
            }
        }
        // The same glyphs the map draws, from the same function.
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            for (k in Kind.entries) Row(verticalAlignment = Alignment.CenterVertically) {
                KindIcon(k, c.inkSecondary, size = 14.dp)
                Text(k.label, Modifier.padding(start = 4.dp), color = c.inkSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun NodeCard(
    e: Entry, links: Int, areaColor: Color,
    onOpen: () -> Unit, onCopy: () -> Unit, onFly: () -> Unit, onClose: () -> Unit,
) {
    val c = LocalCairnColors.current
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp), color = c.grouped, border = BorderStroke(1.dp, c.hairline),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindIcon(e.kind, areaColor)
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

private fun openEntry(ctx: Context, e: Entry, edit: (Entry?) -> Unit) {
    if (e.kind == Kind.REPO || e.kind == Kind.ISSUE || e.kind == Kind.FILE) {
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
