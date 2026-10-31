package app.cairn

import app.cairn.backup.Backup
import app.cairn.backup.BackupException
import app.cairn.data.Kind
import app.cairn.data.NoteStore
import app.cairn.github.GitHubSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StoreBackupTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun firstRunSeedsOnceAndWritesMarkdownPlusIndex() {
        val root = tmp.newFolder("a")
        val store = NoteStore(root)
        store.seedIfFirstRun()
        val notes = store.loadNotes()
        assertTrue(notes.size >= 10)
        assertTrue(notes.all { it.path.startsWith("notes/") && java.io.File(root, it.path).isFile })
        assertTrue(java.io.File(root, "index.json").isFile)
        assertTrue(notes.any { it.kind == Kind.LINK })
        // A second run must not add or restore anything.
        store.delete(notes.first())
        store.seedIfFirstRun()
        assertEquals(notes.size - 1, store.loadNotes().size)
    }

    @Test fun saveEditMoveAndDelete() {
        val store = NoteStore(tmp.newFolder("b"), clock = { 1_000L })
        val n = store.save("Life", "Hello World", "first body", null)
        assertEquals("notes/Life/hello-world.md", n.path)
        assertEquals("first body", NoteStore.split(store.read(n), "").second)
        val renamed = store.save("Money", "Hello World", "second", n)
        assertEquals("notes/Money/hello-world.md", renamed.path)
        assertEquals(1, store.loadNotes().size)
        val dup = store.save("Money", "Hello World", "other", null)
        assertEquals("notes/Money/hello-world-2.md", dup.path)
        store.delete(renamed)
        assertEquals(listOf("Hello World"), store.loadNotes().map { it.title })
    }

    @Test fun corruptIndexIsRebuiltFromFiles() {
        val root = tmp.newFolder("c")
        val store = NoteStore(root)
        store.save("Code", "Keep me", "text", null)
        java.io.File(root, "index.json").writeText("{ not json")
        assertEquals(listOf("Keep me"), NoteStore(root).loadNotes().map { it.title })
    }

    @Test fun cleaningHelpers() {
        assertEquals("note", NoteStore.slugify("!!!"))
        assertEquals("a-b", NoteStore.slugify("A  B"))
        assertEquals("Life", NoteStore.cleanArea("  /// "))
        assertEquals("a b", NoteStore.cleanArea("a/../b"))
    }

    @Test fun githubIndexIsSeparateAndClearable() {
        val store = NoteStore(tmp.newFolder("d"))
        val items = GitHubSync.parseRepos("""[{"full_name":"o/r","name":"r","description":null,"pushed_at":"2024-05-01T10:00:00Z","html_url":"https://github.com/o/r"}]""")
        store.saveGithub(items)
        assertEquals(1, store.loadGithub().size)
        assertEquals("", store.loadGithub().first().text)
        store.clearGithub()
        assertTrue(store.loadGithub().isEmpty())
    }

    @Test fun parsesIssuesAndSkipsPullRequests() {
        val json = """[
          {"number":8,"title":"Add N4 list","body":"words\n\nmore","state":"open","updated_at":"2024-05-02T10:00:00Z","html_url":"https://github.com/o/r/issues/8"},
          {"number":9,"title":"A PR","body":null,"state":"open","updated_at":"2024-05-02T10:00:00Z","pull_request":{}}
        ]"""
        val issues = GitHubSync.parseIssues(json, "o/r")
        assertEquals(1, issues.size)
        assertEquals("#8 Add N4 list", issues[0].title)
        assertEquals("o/r#8", issues[0].path)
        assertEquals(Kind.ISSUE, issues[0].kind)
        assertTrue(issues[0].text.startsWith("words more"))
    }

    @Test fun backupRoundTripRestoresNotes() {
        val src = tmp.newFolder("src")
        val a = NoteStore(src)
        a.seedIfFirstRun()
        a.saveGithub(GitHubSync.parseRepos("""[{"full_name":"o/r","name":"r","description":"d","pushed_at":"2024-05-01T10:00:00Z"}]"""))
        val before = a.loadNotes().map { it.title }.sorted()
        val file = Backup.encrypt(Backup.pack(src), "correct horse".toCharArray(), iterations = 1000)

        val dst = tmp.newFolder("dst")
        NoteStore(dst).save("Life", "Stale note that restore must replace", "x", null)
        Backup.unpack(Backup.decrypt(file, "correct horse".toCharArray()), dst)
        val b = NoteStore(dst)
        assertEquals(before, b.loadNotes().map { it.title }.sorted())
        assertEquals(1, b.loadGithub().size)
    }

    @Test fun wrongPassphraseOrTamperedFileIsRejected() {
        val file = Backup.encrypt("hello".toByteArray(), "passphrase1".toCharArray(), iterations = 1000)
        try { Backup.decrypt(file, "passphrase2".toCharArray()); fail() } catch (_: BackupException) {}
        val tampered = file.clone().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        try { Backup.decrypt(tampered, "passphrase1".toCharArray()); fail() } catch (_: BackupException) {}
        val badHeader = file.clone().also { it[8] = (it[8].toInt() xor 1).toByte() } // iteration count is authenticated
        try { Backup.decrypt(badHeader, "passphrase1".toCharArray()); fail() } catch (_: BackupException) {}
        try { Backup.decrypt(ByteArray(10), "passphrase1".toCharArray()); fail() } catch (_: BackupException) {}
        assertEquals("hello", String(Backup.decrypt(file, "passphrase1".toCharArray())))
    }

    @Test fun shortPassphraseIsRefusedAndCiphertextHidesPlaintext() {
        try { Backup.encrypt(ByteArray(1), "short".toCharArray()); fail() } catch (_: BackupException) {}
        val file = Backup.encrypt("SECRET-NOTE-TEXT".toByteArray(), "passphrase1".toCharArray(), iterations = 1000)
        assertFalse(String(file, Charsets.ISO_8859_1).contains("SECRET"))
        // Fresh salt and IV each time.
        assertFalse(file.contentEquals(Backup.encrypt("SECRET-NOTE-TEXT".toByteArray(), "passphrase1".toCharArray(), iterations = 1000)))
    }

    @Test fun unpackIgnoresPathsOutsideTheStore() {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("notes/Life/ok.md")); z.write("# ok\n\nbody".toByteArray()); z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("../evil.txt")); z.write("x".toByteArray()); z.closeEntry()
        }
        val parent = tmp.newFolder("p")
        val root = java.io.File(parent, "data").also { it.mkdirs() }
        Backup.unpack(bos.toByteArray(), root)
        assertFalse(java.io.File(parent, "evil.txt").exists())
        assertTrue(java.io.File(root, "notes/Life/ok.md").isFile)
    }
}
