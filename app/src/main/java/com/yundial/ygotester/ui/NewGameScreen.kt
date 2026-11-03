package com.yundial.ygotester.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.yundial.ygotester.data.CardRepository
import com.yundial.ygotester.data.DeckStore

/** Impostazioni di una partita di prova */
data class DuelConfig(
    val deckId: String,
    val difficulty: Int,        // 0 facile, 1 medio, 2 difficile
    val botDeckId: String?,     // null = casuale tra quelli del livello
    val first: Int,             // 0 casuale, 1 io, 2 avversario
)

val difficultyNames = listOf("Facile", "Medio", "Difficile")
private val difficultyDescs = listOf(
    "Gioca per divertirsi: mosse spesso a caso, attacca volentieri, usa poco le risposte.",
    "Gioca per vincere: combo di base, Evocazioni dall'Extra Deck, attacchi ragionati e qualche negazione.",
    "Vuole batterti a tutti i costi: prova più sequenze di gioco prima di scegliere, nega le tue giocate appena può.",
)

@Composable
fun NewGameScreen(onBack: () -> Unit, onEditDecks: () -> Unit, onStart: (DuelConfig) -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var deckId by remember { mutableStateOf<String?>(null) }
    var difficulty by remember { mutableIntStateOf(1) }
    var botDeck by remember { mutableStateOf<String?>(null) }

    fun back() { if (step == 0) onBack() else step-- }
    BackHandler { back() }

    val titles = listOf("1 · Il tuo mazzo", "2 · Difficoltà", "3 · Avversario", "4 · Chi inizia")
    Column(Modifier.fillMaxSize()) {
        TopBar(titles[step], { back() })
        when (step) {
            0 -> StepList {
                if (DeckStore.decks.isEmpty()) {
                    item {
                        Text("Non hai ancora mazzi. Creane uno o importalo dall'archivio.", color = Color.Gray)
                        OutlinedButton(onClick = onEditDecks, modifier = Modifier.padding(top = 12.dp)) { Text("Vai ai mazzi") }
                    }
                }
                items(DeckStore.decks, key = { it.id }) { d ->
                    val probs = remember(d) { DeckStore.problems(d) }
                    ChoiceCard(
                        selected = deckId == d.id,
                        title = d.name,
                        sub = "Main ${d.main.size} · Extra ${d.extra.size}" + if (probs.isNotEmpty()) "\n⚠ ${probs.first()}" else "",
                        cover = d.main.firstOrNull { CardRepository.get(it)?.isMonster == true } ?: 0,
                        enabled = d.main.isNotEmpty(),
                    ) { deckId = d.id; step = 1 }
                }
            }
            1 -> StepList {
                items(3) { i ->
                    ChoiceCard(selected = difficulty == i, title = difficultyNames[i], sub = difficultyDescs[i]) {
                        difficulty = i; botDeck = null; step = 2
                    }
                }
            }
            2 -> StepList {
                item {
                    ChoiceCard(selected = botDeck == null, title = "Casuale",
                        sub = "Un mazzo a sorpresa del livello ${difficultyNames[difficulty]}") { botDeck = null; step = 3 }
                }
                items(DeckStore.botDecks.filter { it.level == difficulty }, key = { it.id }) { b ->
                    ChoiceCard(selected = botDeck == b.id, title = b.name, sub = b.desc,
                        cover = b.main.firstOrNull { CardRepository.get(it)?.isMonster == true } ?: 0) { botDeck = b.id; step = 3 }
                }
            }
            else -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val mine = DeckStore.get(deckId ?: "")
                Text("Il tuo mazzo: ${mine?.name ?: "-"}", style = MaterialTheme.typography.bodyLarge)
                Text("Difficoltà: ${difficultyNames[difficulty]}", style = MaterialTheme.typography.bodyLarge)
                Text("Avversario: ${DeckStore.botDecks.firstOrNull { it.id == botDeck }?.name ?: "casuale"}", style = MaterialTheme.typography.bodyLarge)
                Text("Chi inizia il duello?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                listOf("Testa o croce", "Inizio io", "Inizia l'avversario").forEachIndexed { i, label ->
                    Button(
                        onClick = { deckId?.let { id -> onStart(DuelConfig(id, difficulty, botDeck, i)) } },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun StepList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
        content = content
    )
}

@Composable
private fun ChoiceCard(selected: Boolean, title: String, sub: String, cover: Int = -1, enabled: Boolean = true, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = enabled,
        colors = CardDefaults.cardColors(containerColor = PanelLight),
        border = if (selected) BorderStroke(2.dp, Gold) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (cover >= 0) CardImage(cover, Modifier.width(40.dp).aspectRatio(0.686f).padding(end = 0.dp))
            Column(Modifier.padding(start = if (cover >= 0) 12.dp else 0.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = Color.LightGray, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
