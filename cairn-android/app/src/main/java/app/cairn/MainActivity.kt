package app.cairn

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.cairn.data.Entry
import app.cairn.ui.CairnTheme
import app.cairn.ui.CairnViewModel
import app.cairn.ui.EditorScreen
import app.cairn.ui.LocalCairnColors
import app.cairn.ui.MainScreen
import app.cairn.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: CairnViewModel by viewModels()
    private val openNew = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openNew.value = savedInstanceState == null && wantsNewNote(intent)
        enableEdgeToEdge()
        setContent {
            CairnTheme {
                var screen by rememberSaveable { mutableStateOf("main") }
                var editing by remember { mutableStateOf<Entry?>(null) }
                if (openNew.value) { screen = "editor"; editing = null; openNew.value = false }
                val back = { screen = "main" }
                BackHandler(enabled = screen != "main") { back() }
                Box(Modifier.fillMaxSize().background(LocalCairnColors.current.canvas)) {
                    when (screen) {
                        "editor" -> EditorScreen(vm, editing, onDone = back)
                        "settings" -> SettingsScreen(vm, onBack = back)
                        else -> MainScreen(vm, onEditNote = { editing = it; screen = "editor" }, onSettings = { screen = "settings" })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (wantsNewNote(intent)) openNew.value = true
    }

    /** `cairn://note/new` opens a blank note, like the other Lume apps' quick-note links. */
    private fun wantsNewNote(i: Intent) = i.data?.let { it.scheme == "cairn" && it.host == "note" } == true
}
