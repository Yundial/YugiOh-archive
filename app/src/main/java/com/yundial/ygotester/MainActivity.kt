package com.yundial.ygotester

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.yundial.ygotester.data.CardRepository
import com.yundial.ygotester.data.DeckStore
import com.yundial.ygotester.data.Texts
import com.yundial.ygotester.ui.DeckEditorScreen
import com.yundial.ygotester.ui.DecksScreen
import com.yundial.ygotester.ui.DuelConfig
import com.yundial.ygotester.ui.DuelScreen
import com.yundial.ygotester.ui.HomeScreen
import com.yundial.ygotester.ui.NewGameScreen
import com.yundial.ygotester.ui.SetupScreen
import com.yundial.ygotester.ui.YgoTheme

sealed interface Screen {
    data object Setup : Screen
    data object Home : Screen
    data object Decks : Screen
    data class Editor(val deckId: String) : Screen
    data object NewGame : Screen
    data class Duel(val config: DuelConfig) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Texts.init(applicationContext)
        CardRepository.init(applicationContext)
        DeckStore.init(applicationContext)
        setContent {
            YgoTheme {
                Surface(Modifier.fillMaxSize()) { AppRoot() }
            }
        }
    }
}

@Composable
fun AppRoot() {
    val stack = remember {
        mutableStateListOf<Screen>(if (CardRepository.isReady()) Screen.Home else Screen.Setup)
    }
    val current by remember { androidx.compose.runtime.derivedStateOf { stack.last() } }
    fun go(s: Screen) { stack.add(s) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.size - 1) }
    fun home() { stack.clear(); stack.add(Screen.Home) }

    BackHandler(enabled = stack.size > 1 && current !is Screen.Duel) { back() }

    when (val s = current) {
        Screen.Setup -> SetupScreen(
            firstRun = stack.size == 1,
            onDone = { if (stack.size == 1) home() else back() },
            onBack = if (stack.size > 1) ({ back() }) else null,
        )
        Screen.Home -> HomeScreen(
            onPlay = { go(Screen.NewGame) },
            onDecks = { go(Screen.Decks) },
            onUpdate = { go(Screen.Setup) },
        )
        Screen.Decks -> DecksScreen(onBack = { back() }, onEdit = { go(Screen.Editor(it)) })
        is Screen.Editor -> DeckEditorScreen(deckId = s.deckId, onBack = { back() })
        Screen.NewGame -> NewGameScreen(
            onBack = { back() },
            onEditDecks = { go(Screen.Decks) },
            onStart = { cfg -> go(Screen.Duel(cfg)) },
        )
        is Screen.Duel -> DuelScreen(
            config = s.config,
            onExit = { back() },
            onHome = { home() },
            onRematch = { cfg -> stack.removeAt(stack.size - 1); stack.add(Screen.Duel(cfg)) },
        )
    }
}
