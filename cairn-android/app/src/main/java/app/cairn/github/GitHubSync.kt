package app.cairn.github

import app.cairn.data.Entry
import app.cairn.data.Kind
import app.cairn.search.Doc
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Raised for a bad token, a network failure or a rate limit; the message is safe to show. */
class GitHubException(message: String) : Exception(message)

/** Where GitHub answers come from; a fake in tests, [GitHubApi] on the phone. */
interface GitHubSource {
    fun get(path: String, raw: Boolean = false): String
}

/** Read-only access to the GitHub REST API with a fine-grained token. Nothing is ever written. */
class GitHubApi(private val token: String, private val base: String = "https://api.github.com") : GitHubSource {

    override fun get(path: String, raw: Boolean): String {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", if (raw) "application/vnd.github.raw+json" else "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "Cairn")
            val code = conn.responseCode
            if (code == 404 && raw) return ""
            if (code == 401) throw GitHubException("GitHub rejected the token. Check it and its expiry.")
            if (code == 403 || code == 429) throw GitHubException("GitHub refused the request (rate limit or missing permission).")
            if (code !in 200..299) throw GitHubException("GitHub answered $code.")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: GitHubException) {
            throw e
        } catch (e: java.io.IOException) {
            throw GitHubException("Could not reach GitHub. Check the connection.")
        } finally {
            conn.disconnect()
        }
    }
}

class SyncOptions(
    /** Repos (owner/name) whose files are not indexed; their README and issues still are. */
    val skipFiles: Set<String> = emptySet(),
    val maxRepos: Int = 30,
    val filesPerRepo: Int = 40,
    val maxFileBytes: Int = 200 * 1024,
    /** How many of a repo's indexed files also appear as map nodes. The rest are search-only. */
    val fileNodesPerRepo: Int = 6,
    val maxRepoBytes: Int = 2 * 1024 * 1024,
)

/** Map nodes plus full-text documents from one sync. */
class Snapshot(val entries: List<Entry>, val docs: List<Doc>)

class RepoInfo(val entry: Entry, val defaultBranch: String)

class TreeItem(val path: String, val size: Long)

object GitHubSync {
    const val AREA = "GitHub"
    private const val README_NODE_TEXT = 4000
    private const val DOC_LIMIT = 200 * 1024
    private const val ISSUES_PER_REPO = 30

    private val TEXT_EXT = setOf(
        "md", "markdown", "txt", "rst", "adoc", "kt", "kts", "java", "py", "js", "mjs", "ts", "tsx", "jsx", "json", "yml", "yaml",
        "toml", "xml", "html", "htm", "css", "scss", "sh", "bash", "gradle", "properties", "c", "h", "cpp", "hpp", "cc", "cs",
        "go", "rs", "rb", "php", "swift", "sql", "lm", "cfg", "ini", "conf", "tf", "dart", "lua", "r", "m",
    )
    private val TEXT_NAMES = setOf("license", "makefile", "dockerfile", "readme", "changelog", "notice", "authors")
    private val SKIP_DIRS = setOf(
        "node_modules", "vendor", "build", "dist", "out", "target", ".git", ".gradle", ".idea", "pods", "__pycache__",
        ".next", "venv", ".venv", "third_party", "gradle", "bin", "obj", ".dart_tool", "coverage",
    )
    private val SKIP_FILES = setOf("package-lock.json", "yarn.lock", "pnpm-lock.yaml", "gradlew", "gradlew.bat", "gradle-wrapper.jar")

    /** Fetches repos, READMEs, issues and (unless skipped) the text files of each repo. */
    fun fetch(src: GitHubSource, opts: SyncOptions = SyncOptions(), progress: (String) -> Unit = {}): Snapshot {
        progress("Reading repositories")
        val repos = parseRepos(src.get("/user/repos?per_page=${opts.maxRepos}&sort=pushed&affiliation=owner,collaborator,organization_member"))
        val entries = ArrayList<Entry>()
        val docs = ArrayList<Doc>()
        val pool = Executors.newFixedThreadPool(4)
        try {
            for ((i, info) in repos.withIndex()) {
                val full = info.entry.path
                progress("Indexing $full (${i + 1}/${repos.size})")
                val readme = try { src.get("/repos/$full/readme", raw = true) } catch (e: GitHubException) { "" }
                val desc = info.entry.text
                entries += info.entry.copy(text = collapse("$desc $readme").take(README_NODE_TEXT))
                docs += Doc(info.entry.id, info.entry.id, Kind.REPO, info.entry.title, full, "$desc\n$readme".take(DOC_LIMIT), full)

                val issues = try { parseIssues(src.get("/repos/$full/issues?state=all&per_page=$ISSUES_PER_REPO"), full) } catch (e: GitHubException) { emptyList() }
                for ((entry, body) in issues) {
                    entries += entry
                    docs += Doc(entry.id, entry.id, Kind.ISSUE, entry.title, entry.path, body.take(20_000), full)
                }

                if (full in opts.skipFiles) continue
                val tree = try { parseTree(src.get("/repos/$full/git/trees/${info.defaultBranch}?recursive=1")) } catch (e: GitHubException) { emptyList() }
                val chosen = selectFiles(tree, opts)
                val fetched = pool.invokeAll(chosen.map { item ->
                    Callable {
                        try {
                            val text = src.get("/repos/$full/contents/${encodePath(item.path)}?ref=${URLEncoder.encode(info.defaultBranch, "UTF-8")}", raw = true)
                            if (text.isEmpty() || text.take(4096).contains('\u0000')) null else item to text.take(DOC_LIMIT)
                        } catch (e: GitHubException) { null }
                    }
                }).mapNotNull { it.get() }
                var bytes = 0
                var nodes = 0
                for ((item, text) in fetched) {
                    bytes += text.length
                    if (bytes > opts.maxRepoBytes) break
                    val id = "gh:$full:${item.path}"
                    val name = item.path.substringAfterLast('/')
                    val asNode = nodes < opts.fileNodesPerRepo
                    if (asNode) {
                        nodes++
                        entries += Entry(id, Kind.FILE, AREA, name, "$full/${item.path}", collapse(text).take(300), info.entry.updatedAt,
                            "https://github.com/$full/blob/${info.defaultBranch}/${item.path}")
                    }
                    docs += Doc(id, if (asNode) id else info.entry.id, Kind.FILE, name, "$full/${item.path}", text, full)
                }
            }
        } finally {
            pool.shutdown()
        }
        return Snapshot(entries, docs)
    }

    fun parseRepos(json: String): List<RepoInfo> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            val full = o.getString("full_name")
            RepoInfo(
                Entry(
                    id = "gh:$full", kind = Kind.REPO, area = AREA, title = o.getString("name"), path = full,
                    text = if (o.isNull("description")) "" else o.optString("description", ""),
                    updatedAt = time(o.optString("pushed_at")), url = o.optString("html_url").ifEmpty { null },
                ),
                o.optString("default_branch", "main").ifEmpty { "main" },
            )
        }
    }

    /** Issues (not pull requests) as a map entry plus the full body for search. */
    fun parseIssues(json: String, repoPath: String): List<Pair<Entry, String>> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { !it.has("pull_request") }
            .map { o ->
                val num = o.getInt("number")
                val body = if (o.isNull("body")) "" else o.optString("body", "")
                val title = o.getString("title")
                Entry(
                    id = "gh:$repoPath#$num", kind = Kind.ISSUE, area = AREA,
                    title = "#$num $title", path = "$repoPath#$num",
                    text = collapse(body).take(1000) + " " + o.optString("state"),
                    updatedAt = time(o.optString("updated_at")), url = o.optString("html_url").ifEmpty { null },
                ) to "$title\n$body ${o.optString("state")}"
            }
    }

    fun parseTree(json: String): List<TreeItem> {
        val arr = org.json.JSONObject(json).optJSONArray("tree") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("type") == "blob" }
            .map { TreeItem(it.getString("path"), it.optLong("size")) }
    }

    /** Text files worth indexing: small, not vendored or built, shallowest first, capped per repo. */
    fun selectFiles(tree: List<TreeItem>, opts: SyncOptions): List<TreeItem> =
        tree.filter { isIndexable(it, opts) }
            .sortedWith(compareBy({ it.path.count { c -> c == '/' } }, { it.path.length }, { it.path }))
            .take(opts.filesPerRepo)

    fun isIndexable(item: TreeItem, opts: SyncOptions = SyncOptions()): Boolean {
        if (item.size <= 0 || item.size > opts.maxFileBytes) return false
        val parts = item.path.split('/')
        if (parts.dropLast(1).any { it.lowercase() in SKIP_DIRS }) return false
        val name = parts.last().lowercase()
        if (name in SKIP_FILES || name.endsWith(".min.js") || name.endsWith(".min.css") || name.endsWith(".lock")) return false
        if (name.startsWith("readme") && parts.size == 1) return false // already indexed with the repo
        val ext = name.substringAfterLast('.', "")
        return if (ext.isEmpty() || ext == name) name in TEXT_NAMES else ext in TEXT_EXT
    }

    private fun encodePath(p: String) = p.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun collapse(s: String) = s.replace(Regex("\\s+"), " ").trim()

    private fun time(s: String): Long = try { Instant.parse(s).toEpochMilli() } catch (_: Exception) { System.currentTimeMillis() }
}
