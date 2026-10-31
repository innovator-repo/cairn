package app.cairn.github

import app.cairn.data.Entry
import app.cairn.data.Kind
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** Raised for a bad token, a network failure or a rate limit; the message is safe to show. */
class GitHubException(message: String) : Exception(message)

/** Read-only access to the GitHub REST API with a fine-grained token. Nothing is ever written. */
class GitHubApi(private val token: String, private val base: String = "https://api.github.com") {

    fun get(path: String, raw: Boolean = false): String {
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

object GitHubSync {
    const val AREA = "GitHub"
    private const val MAX_REPOS = 30
    private const val README_LIMIT = 4000
    private const val ISSUES_PER_REPO = 30

    /** Fetches repos, READMEs and issues. [progress] gets a short status line. */
    fun fetch(api: GitHubApi, progress: (String) -> Unit = {}): List<Entry> {
        progress("Reading repositories")
        val repos = parseRepos(api.get("/user/repos?per_page=$MAX_REPOS&sort=pushed&affiliation=owner,collaborator,organization_member"))
        val out = ArrayList<Entry>()
        for ((i, repo) in repos.withIndex()) {
            progress("Indexing ${repo.path} (${i + 1}/${repos.size})")
            val readme = try { api.get("/repos/${repo.path}/readme", raw = true) } catch (e: GitHubException) { "" }
            out += repo.copy(text = (repo.text + " " + readme).replace(Regex("\\s+"), " ").trim().take(README_LIMIT))
            val issues = try { api.get("/repos/${repo.path}/issues?state=all&per_page=$ISSUES_PER_REPO") } catch (e: GitHubException) { "[]" }
            out += parseIssues(issues, repo.path)
        }
        return out
    }

    fun parseRepos(json: String): List<Entry> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            val full = o.getString("full_name")
            Entry(
                id = "gh:$full", kind = Kind.REPO, area = AREA, title = o.getString("name"), path = full,
                text = o.optString("description", "").takeIf { it != "null" }.orEmpty(),
                updatedAt = time(o.optString("pushed_at")), url = o.optString("html_url").ifEmpty { null },
            )
        }
    }

    fun parseIssues(json: String, repoPath: String): List<Entry> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { !it.has("pull_request") }
            .map { o ->
                val num = o.getInt("number")
                Entry(
                    id = "gh:$repoPath#$num", kind = Kind.ISSUE, area = AREA,
                    title = "#$num ${o.getString("title")}", path = "$repoPath#$num",
                    text = (o.optString("body", "").takeIf { it != "null" }.orEmpty())
                        .replace(Regex("\\s+"), " ").trim().take(1000) + " " + o.optString("state"),
                    updatedAt = time(o.optString("updated_at")), url = o.optString("html_url").ifEmpty { null },
                )
            }
    }

    private fun time(s: String): Long = try { Instant.parse(s).toEpochMilli() } catch (_: Exception) { System.currentTimeMillis() }

}
