package app.cairn.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.cairn.backup.Backup
import app.cairn.backup.BackupException
import app.cairn.data.Entry
import app.cairn.data.Kind
import app.cairn.data.NoteStore
import app.cairn.github.GitHubApi
import app.cairn.github.GitHubException
import app.cairn.github.GitHubSync
import app.cairn.github.SyncOptions
import app.cairn.github.TokenVault
import app.cairn.graph.Graph
import app.cairn.search.Doc
import app.cairn.search.FullTextIndex
import app.cairn.search.TextHit
import app.cairn.search.TfidfEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CairnViewModel(app: Application) : AndroidViewModel(app) {
    private val store = NoteStore(File(app.filesDir, "cairn"))
    private val vault = TokenVault(app)
    private val prefs = app.getSharedPreferences("cairn_prefs", Context.MODE_PRIVATE)

    var graph by mutableStateOf(Graph.EMPTY); private set
    var hasToken by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    /** One short line for the drawer summary and settings. */
    var message by mutableStateOf(""); private set
    var lastSync by mutableStateOf<Long?>(null); private set
    /** Repos whose files are not indexed. Takes effect on the next sync. */
    var skipFiles by mutableStateOf(prefs.getStringSet(KEY_SKIP, emptySet()) ?: emptySet()); private set
    var showFrameTimes by mutableStateOf(prefs.getBoolean(KEY_DEBUG, false)); private set

    /** A plain copy of [hasToken] that background threads can read safely. */
    @Volatile private var githubOn = false
    private var noteIndex = FullTextIndex()
    private var ghIndex = FullTextIndex()

    init {
        hasToken = vault.has() && vault.load() != null
        githubOn = hasToken
        if (!hasToken) store.clearGithub()
        viewModelScope.launch {
            val token = hasToken
            val loaded = withContext(Dispatchers.IO) { store.seedIfFirstRun(); if (token) store.loadGithubIndex() else FullTextIndex() }
            ghIndex = loaded
            rebuild()
        }
    }

    private suspend fun rebuild() {
        val withGithub = hasToken // read on the main thread; state is not safe to read elsewhere
        val result = withContext(Dispatchers.Default) {
            val notes = store.loadNotes()
            val entries = notes + if (withGithub) store.loadGithub() else emptyList()
            val idx = FullTextIndex()
            idx.addAll(notes.map { Doc(it.id, it.id, it.kind, it.title, it.path, it.text) })
            Triple(Graph.build(entries, TfidfEmbedder(512)), idx, entries)
        }
        graph = result.first
        noteIndex = result.second
    }

    /** Search notes, repos, issues and files; best first, each with a highlighted snippet. */
    fun search(query: String): List<TextHit> {
        val a = noteIndex.search(query, 40)
        val b = if (githubOn) ghIndex.search(query, 40) else emptyList()
        return (a + b).sortedByDescending { it.score }.take(60)
    }

    fun noteBody(entry: Entry): String = NoteStore.split(store.read(entry), entry.title).second

    fun saveNote(area: String, title: String, body: String, existing: Entry?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.save(area, title, body, existing) }
            rebuild()
        }
    }

    fun deleteNote(entry: Entry) {
        viewModelScope.launch { withContext(Dispatchers.IO) { store.delete(entry) }; rebuild() }
    }

    fun areaNames(): List<String> = graph.areas.map { it.name }.filter { it != GitHubSync.AREA }

    /** Indexed repos, for the per-repo file switch in Settings. */
    fun repos(): List<Entry> = graph.entries.filter { it.kind == Kind.REPO }

    fun setFilesIndexed(repo: String, on: Boolean) {
        skipFiles = if (on) skipFiles - repo else skipFiles + repo
        prefs.edit().putStringSet(KEY_SKIP, skipFiles).apply()
    }

    fun setFrameTimes(on: Boolean) {
        showFrameTimes = on
        prefs.edit().putBoolean(KEY_DEBUG, on).apply()
    }

    fun saveToken(token: String) {
        if (token.isBlank()) return
        vault.save(token)
        hasToken = true
        githubOn = true
        sync()
    }

    fun removeToken() {
        vault.clear()
        store.clearGithub()
        ghIndex = FullTextIndex()
        hasToken = false
        githubOn = false
        lastSync = null
        message = "GitHub removed"
        viewModelScope.launch { rebuild() }
    }

    fun sync() {
        val token = vault.load() ?: return
        if (busy) return
        busy = true
        val skip = skipFiles
        viewModelScope.launch {
            try {
                val snap = withContext(Dispatchers.IO) {
                    GitHubSync.fetch(GitHubApi(token), SyncOptions(skipFiles = skip)) { line -> message = line }
                }
                val idx = withContext(Dispatchers.Default) { FullTextIndex().also { it.addAll(snap.docs) } }
                withContext(Dispatchers.IO) { store.saveGithub(snap.entries); store.saveGithubIndex(idx) }
                ghIndex = idx
                lastSync = System.currentTimeMillis()
                message = "Indexed ${snap.docs.size} GitHub documents"
                rebuild()
            } catch (e: GitHubException) {
                message = e.message ?: "GitHub sync failed"
            } finally {
                busy = false
            }
        }
    }

    fun createBackup(uri: Uri, passphrase: CharArray) {
        busy = true
        viewModelScope.launch {
            message = try {
                withContext(Dispatchers.IO) {
                    val bytes = Backup.encrypt(Backup.pack(store.dataRoot()), passphrase)
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                }
                "Backup saved"
            } catch (e: BackupException) {
                e.message ?: "Backup failed"
            } catch (e: Exception) {
                "Could not write the backup file"
            } finally {
                passphrase.fill('\u0000'); busy = false
            }
        }
    }

    fun restoreBackup(uri: Uri, passphrase: CharArray) {
        busy = true
        viewModelScope.launch {
            message = try {
                withContext(Dispatchers.IO) {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    Backup.unpack(Backup.decrypt(bytes, passphrase), store.dataRoot())
                    ghIndex = store.loadGithubIndex()
                }
                rebuild()
                "Backup restored"
            } catch (e: BackupException) {
                e.message ?: "Restore failed"
            } catch (e: Exception) {
                "Could not read the backup file"
            } finally {
                passphrase.fill('\u0000'); busy = false
            }
        }
    }

    private companion object {
        const val KEY_SKIP = "skip_files"
        const val KEY_DEBUG = "frame_times"
    }
}
