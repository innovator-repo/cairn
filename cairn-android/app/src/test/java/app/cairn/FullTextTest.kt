package app.cairn

import app.cairn.data.Kind
import app.cairn.github.GitHubException
import app.cairn.github.GitHubSource
import app.cairn.github.GitHubSync
import app.cairn.github.SyncOptions
import app.cairn.github.TreeItem
import app.cairn.search.Doc
import app.cairn.search.FullTextIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A fake GitHub: fixed JSON for a repo with a README, an issue, a source file, a binary and vendored code. */
class FakeGitHub : GitHubSource {
    val calls = java.util.Collections.synchronizedList(ArrayList<String>())
    override fun get(path: String, raw: Boolean): String {
        calls += path
        return when {
            path.startsWith("/user/repos") -> """[
              {"full_name":"me/atlas","name":"atlas","description":"Mapping toolkit","default_branch":"trunk","pushed_at":"2024-06-01T10:00:00Z","html_url":"https://github.com/me/atlas"},
              {"full_name":"me/secret","name":"secret","description":null,"default_branch":"main","pushed_at":"2024-05-01T10:00:00Z","html_url":"https://github.com/me/secret"}]"""
            path == "/repos/me/atlas/readme" -> "# Atlas\n\nUse the zeppelin protocol for navigation."
            path == "/repos/me/secret/readme" -> "Quokka handbook"
            path.startsWith("/repos/me/atlas/issues") -> """[
              {"number":3,"title":"Crash on startup","body":"Stack trace mentions the flamingo module.","state":"open","updated_at":"2024-06-02T10:00:00Z","html_url":"https://github.com/me/atlas/issues/3"},
              {"number":4,"title":"A pull request","body":"ignored","state":"open","updated_at":"2024-06-02T10:00:00Z","pull_request":{}}]"""
            path.startsWith("/repos/me/secret/issues") -> "[]"
            path.startsWith("/repos/me/atlas/git/trees/trunk") -> """{"tree":[
              {"path":"src/Router.kt","type":"blob","size":900},
              {"path":"src/Logo.png","type":"blob","size":900},
              {"path":"node_modules/lib/index.js","type":"blob","size":900},
              {"path":"huge/Big.kt","type":"blob","size":900000},
              {"path":"docs","type":"tree"},
              {"path":"docs/guide.md","type":"blob","size":300}]}"""
            path.startsWith("/repos/me/secret/git/trees") -> """{"tree":[{"path":"notes.txt","type":"blob","size":50}]}"""
            path.startsWith("/repos/me/atlas/contents/src/Router.kt") -> "class RouteResolver { fun findQuasarPath() = 1 }"
            path.startsWith("/repos/me/atlas/contents/docs/guide.md") -> "The guide explains pelican setup."
            path.startsWith("/repos/me/atlas/contents/src/Logo.png") -> "\u0000PNG binary"
            path.startsWith("/repos/me/secret/contents/") -> "walrus"
            else -> throw GitHubException("unexpected $path")
        }
    }
}

class FullTextTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun synced(opts: SyncOptions = SyncOptions()): Pair<FakeGitHub, FullTextIndex> {
        val fake = FakeGitHub()
        val snap = GitHubSync.fetch(fake, opts)
        return fake to FullTextIndex().also { it.addAll(snap.docs) }
    }

    @Test fun findsAWordInsideAReadme() {
        val (_, idx) = synced()
        val hit = idx.search("zeppelin").first()
        assertEquals("gh:me/atlas", hit.doc.id)
        assertEquals(Kind.REPO, hit.doc.kind)
        assertTrue(hit.snippet.contains("zeppelin"))
        assertEquals("zeppelin", hit.snippet.substring(hit.marks.first()))
    }

    @Test fun findsAWordInsideASourceFileAndCamelCasePieces() {
        val (_, idx) = synced()
        val hit = idx.search("quasar").first()
        assertEquals(Kind.FILE, hit.doc.kind)
        assertEquals("me/atlas/src/Router.kt", hit.doc.path)
        assertEquals("me/atlas", hit.doc.repo)
        assertTrue(hit.snippet.contains("Quasar"))
        assertEquals("Quasar", hit.snippet.substring(hit.marks.first()).take(6))
        assertEquals("gh:me/atlas:src/Router.kt", idx.search("routeresolver").first().doc.id)
    }

    @Test fun findsIssueBodiesAndMarkdownFiles() {
        val (_, idx) = synced()
        assertEquals("gh:me/atlas#3", idx.search("flamingo").first().doc.id)
        assertEquals("gh:me/atlas:docs/guide.md", idx.search("pelican").first().doc.id)
    }

    @Test fun skipsBinariesVendoredCodeHugeFilesAndPullRequests() {
        val (_, idx) = synced()
        assertTrue(idx.search("PNG").none { it.doc.kind == Kind.FILE })
        assertTrue(idx.documents.none { it.path.contains("node_modules") || it.path.contains("Logo.png") || it.path.contains("Big.kt") })
        assertTrue(idx.search("ignored").isEmpty())
    }

    @Test fun repoFileSwitchSkipsFilesButKeepsReadmeAndIssues() {
        val (fake, idx) = synced(SyncOptions(skipFiles = setOf("me/atlas")))
        assertTrue(idx.search("quasar").isEmpty())
        assertEquals("gh:me/atlas", idx.search("zeppelin").first().doc.id)
        assertEquals("gh:me/atlas#3", idx.search("flamingo").first().doc.id)
        assertTrue(fake.calls.none { it.startsWith("/repos/me/atlas/contents") })
        // The other repo still has its files indexed.
        assertEquals("walrus", idx.search("walrus").first().doc.text)
    }

    @Test fun fileNodesAreCappedButEveryFileIsSearchable() {
        val snap = GitHubSync.fetch(FakeGitHub(), SyncOptions(fileNodesPerRepo = 1))
        assertEquals(1, snap.entries.count { it.kind == Kind.FILE && it.path.startsWith("me/atlas") })
        val idx = FullTextIndex().also { it.addAll(snap.docs) }
        val pelican = idx.search("pelican").first().doc
        assertEquals("gh:me/atlas:docs/guide.md", pelican.id)
        // The file without a node of its own points at its repo so the map can still fly there.
        val nodeIds = snap.entries.map { it.id }.toSet()
        assertTrue(pelican.entryId in nodeIds)
    }

    @Test fun prefixAndMultiWordQueries() {
        val (_, idx) = synced()
        assertEquals("gh:me/atlas", idx.search("zeppe").first().doc.id)
        assertEquals("gh:me/atlas", idx.search("zeppelin navigation").first().doc.id)
        assertTrue(idx.search("nothingmatchesthis").isEmpty())
        assertTrue(idx.search("   ").isEmpty())
    }

    @Test fun indexSurvivesSaveAndLoad() {
        val (_, idx) = synced()
        val f = tmp.newFile("docs.bin")
        idx.save(f)
        val loaded = FullTextIndex.load(f)
        assertEquals(idx.size, loaded.size)
        assertEquals("gh:me/atlas:src/Router.kt", loaded.search("quasar").first().doc.id)
        f.writeText("garbage")
        assertEquals(0, FullTextIndex.load(f).size)
        assertEquals(0, FullTextIndex.load(java.io.File(tmp.root, "missing")).size)
    }

    @Test fun treeSelectionRules() {
        val o = SyncOptions()
        assertFalse(GitHubSync.isIndexable(TreeItem("a/build/x.kt", 10), o))
        assertFalse(GitHubSync.isIndexable(TreeItem("x.min.js", 10), o))
        assertFalse(GitHubSync.isIndexable(TreeItem("README.md", 10), o))
        assertFalse(GitHubSync.isIndexable(TreeItem("img.png", 10), o))
        assertFalse(GitHubSync.isIndexable(TreeItem("big.kt", 300 * 1024), o))
        assertTrue(GitHubSync.isIndexable(TreeItem("docs/README.md", 10), o))
        assertTrue(GitHubSync.isIndexable(TreeItem("Makefile", 10), o))
        val many = (1..100).map { TreeItem("d$it/f.kt", 10) } + TreeItem("top.kt", 10)
        val picked = GitHubSync.selectFiles(many, SyncOptions(filesPerRepo = 5))
        assertEquals(5, picked.size)
        assertEquals("top.kt", picked.first().path) // shallowest first
    }

    @Test fun notesAndCodeShareTheSameIndex() {
        val idx = FullTextIndex()
        idx.add(Doc("n1", "n1", Kind.NOTE, "Flight to Tokyo", "notes/Travel/flight.md", "seat 23A"))
        idx.add(Doc("n2", "n2", Kind.NOTE, "Gradle tips", "notes/Code/gradle.md", "use the wrapper"))
        assertEquals("n1", idx.search("tokyo").first().doc.id)
        assertEquals("n2", idx.search("notes/code").first().doc.id)
        assertNotNull(idx.search("wrapper").firstOrNull())
    }
}
