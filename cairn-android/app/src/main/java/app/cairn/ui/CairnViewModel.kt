package app.cairn.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.cairn.backup.Backup
import app.cairn.backup.BackupException
import app.cairn.data.Entry
import app.cairn.data.NoteStore
import app.cairn.github.GitHubApi
import app.cairn.github.GitHubException
import app.cairn.github.GitHubSync
import app.cairn.github.TokenVault
import app.cairn.graph.Graph
import app.cairn.search.Hit
import app.cairn.search.SearchEngine
import app.cairn.search.TfidfEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CairnViewModel(app: Application) : AndroidViewModel(app) {
    private val store = NoteStore(File(app.filesDir, "cairn"))
    private val vault = TokenVault(app)

    var graph by mutableStateOf(Graph.EMPTY); private set
    var hasToken by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    /** One short line for the drawer summary and settings. */
    var message by mutableStateOf(""); private set
    var lastSync by mutableStateOf<Long?>(null); private set

    private var engine: SearchEngine? = null

    init {
        hasToken = vault.has() && vault.load() != null
        if (!hasToken) store.clearGithub()
        viewModelScope.launch { withContext(Dispatchers.IO) { store.seedIfFirstRun() }; rebuild() }
    }

    private suspend fun rebuild() {
        val (g, e) = withContext(Dispatchers.Default) {
            val entries = store.loadNotes() + if (hasToken) store.loadGithub() else emptyList()
            Graph.build(entries, TfidfEmbedder(512)) to SearchEngine(entries, TfidfEmbedder(512))
        }
        graph = g
        engine = e
    }

    fun search(query: String): List<Hit> = engine?.search(query) ?: emptyList()

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

    fun saveToken(token: String) {
        if (token.isBlank()) return
        vault.save(token)
        hasToken = true
        sync()
    }

    fun removeToken() {
        vault.clear()
        store.clearGithub()
        hasToken = false
        lastSync = null
        message = "GitHub removed"
        viewModelScope.launch { rebuild() }
    }

    fun sync() {
        val token = vault.load() ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    GitHubSync.fetch(GitHubApi(token)) { line -> message = line }
                }
                withContext(Dispatchers.IO) { store.saveGithub(items) }
                lastSync = System.currentTimeMillis()
                message = "Indexed ${items.size} GitHub items"
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
}
