package app.cairn.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Notes live as markdown files under `notes/<area>/<slug>.md` in app storage. `index.json` is a cache
 * of their titles and text for search and the map, rebuilt from the files whenever it is missing or
 * unreadable. GitHub items are kept in their own `github.json`, so removing the token can clear them
 * without touching a note.
 */
class NoteStore(private val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val notesDir = File(root, "notes")
    private val indexFile = File(root, "index.json")
    private val githubFile = File(root, "github.json")
    private val seededMarker = File(root, ".seeded")

    init { root.mkdirs() }

    fun loadNotes(): List<Entry> {
        val cached = readIndex()
        if (cached != null && cached.all { File(root, it.path).isFile }) return cached
        return reindex()
    }

    /** Rebuilds the index from the markdown files. */
    fun reindex(): List<Entry> {
        val out = ArrayList<Entry>()
        notesDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
            dir.listFiles { f -> f.isFile && f.extension == "md" }?.sortedBy { it.name }?.forEach { f ->
                out += entryFor(dir.name, f)
            }
        }
        writeIndex(out)
        return out
    }

    fun read(entry: Entry): String = File(root, entry.path).takeIf { it.isFile }?.readText() ?: ""

    /** Creates a note, or rewrites [existing] (moving the file if the title or area changed). */
    fun save(area: String, title: String, body: String, existing: Entry? = null): Entry {
        val areaName = cleanArea(area)
        val dir = File(notesDir, areaName).also { it.mkdirs() }
        val file = if (existing != null && existing.area == areaName && slugify(existing.title) == slugify(title)) {
            File(root, existing.path)
        } else {
            existing?.let { File(root, it.path).delete() }
            uniqueFile(dir, slugify(title))
        }
        file.writeText(compose(title, body))
        file.setLastModified(clock())
        reindex()
        return entryFor(areaName, file)
    }

    fun delete(entry: Entry) {
        File(root, entry.path).delete()
        reindex()
    }

    /** Writes the sample set once, on first run. */
    fun seedIfFirstRun() {
        if (seededMarker.exists()) return
        seededMarker.writeText("1")
        if (notesDir.exists() && !notesDir.list().isNullOrEmpty()) return
        val now = clock()
        for (s in Seed.notes) {
            val dir = File(notesDir, s.area).also { it.mkdirs() }
            val f = uniqueFile(dir, slugify(s.title))
            f.writeText(compose(s.title, s.body))
            f.setLastModified(now - s.ageDays * DAY)
        }
        reindex()
    }

    fun loadGithub(): List<Entry> = readEntries(githubFile) ?: emptyList()

    fun saveGithub(entries: List<Entry>) {
        githubFile.writeText(encode(entries).toString())
    }

    fun clearGithub() { githubFile.delete() }

    /** Directory that backups pack and restore. */
    fun dataRoot(): File = root

    private fun entryFor(areaDir: String, f: File): Entry {
        val raw = f.readText()
        val (title, body) = split(raw, f.nameWithoutExtension)
        val first = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val kind = if (first.startsWith("http://") || first.startsWith("https://")) Kind.LINK else Kind.NOTE
        return Entry(
            id = "notes/$areaDir/${f.name}",
            kind = kind,
            area = areaDir,
            title = title,
            path = "notes/$areaDir/${f.name}",
            text = body.replace(Regex("\\s+"), " ").trim().take(TEXT_LIMIT),
            updatedAt = f.lastModified(),
            url = if (kind == Kind.LINK) first.split(" ").first() else null,
        )
    }

    private fun readIndex(): List<Entry>? = readEntries(indexFile)

    private fun writeIndex(entries: List<Entry>) { indexFile.writeText(encode(entries).toString()) }

    private fun uniqueFile(dir: File, slug: String): File {
        var f = File(dir, "$slug.md")
        var n = 2
        while (f.exists()) f = File(dir, "$slug-${n++}.md")
        return f
    }

    companion object {
        const val DAY = 86_400_000L
        const val TEXT_LIMIT = 2000

        fun compose(title: String, body: String) = "# ${title.trim()}\n\n${body.trim()}\n"

        /** Title from the first `# ` line, if there is one. */
        fun split(raw: String, fallbackTitle: String): Pair<String, String> {
            val lines = raw.lines()
            val i = lines.indexOfFirst { it.isNotBlank() }
            if (i >= 0 && lines[i].startsWith("# ")) {
                return lines[i].removePrefix("# ").trim() to lines.drop(i + 1).joinToString("\n").trim()
            }
            return fallbackTitle to raw.trim()
        }

        fun slugify(s: String): String {
            val slug = s.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
                .replace(Regex("-+"), "-").trim('-')
            return slug.ifEmpty { "note" }.take(60)
        }

        fun cleanArea(s: String): String {
            val a = s.trim().map { if (it.isLetterOrDigit() || it == ' ' || it == '-') it else ' ' }
                .joinToString("").replace(Regex("\\s+"), " ").trim().take(30)
            return a.ifEmpty { "Life" }
        }

        fun encode(entries: List<Entry>): JSONObject {
            val arr = JSONArray()
            for (e in entries) arr.put(JSONObject().apply {
                put("id", e.id); put("kind", e.kind.name); put("area", e.area); put("title", e.title)
                put("path", e.path); put("text", e.text); put("updatedAt", e.updatedAt)
                e.url?.let { put("url", it) }
            })
            return JSONObject().put("version", 1).put("entries", arr)
        }

        fun decode(json: String): List<Entry> {
            val arr = JSONObject(json).getJSONArray("entries")
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    id = o.getString("id"), kind = Kind.parse(o.optString("kind")), area = o.getString("area"),
                    title = o.getString("title"), path = o.getString("path"), text = o.optString("text"),
                    updatedAt = o.optLong("updatedAt"), url = if (o.has("url")) o.getString("url") else null,
                )
            }
        }

        private fun readEntries(f: File): List<Entry>? =
            try { if (f.isFile) decode(f.readText()) else null } catch (_: Exception) { null }
    }
}
