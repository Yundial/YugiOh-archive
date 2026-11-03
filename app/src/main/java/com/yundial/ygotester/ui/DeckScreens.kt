@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.yundial.ygotester.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yundial.ygotester.data.CardInfo
import com.yundial.ygotester.data.CardRepository
import com.yundial.ygotester.data.Deck
import com.yundial.ygotester.data.DeckStore
import com.yundial.ygotester.data.Texts
import com.yundial.ygotester.data.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ archivio mazzi
@Composable
fun DecksScreen(onBack: () -> Unit, onEdit: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var importing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Deck?>(null) }
    var deleting by remember { mutableStateOf<Deck?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("I miei mazzi", onBack) {
                TextButton(onClick = { importing = true }) { Text("Importa .ydk") }
            }
            info?.let { Text(it, Modifier.padding(12.dp), color = Gold) }
            if (DeckStore.decks.isEmpty()) {
                Text("Nessun mazzo in archivio. Creane uno con il tasto in basso oppure importa una lista .ydk.",
                    Modifier.padding(24.dp), color = Color.Gray)
            }
            LazyColumn(contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DeckStore.decks, key = { it.id }) { d ->
                    var menu by remember { mutableStateOf(false) }
                    val probs = remember(d) { DeckStore.problems(d) }
                    Card(onClick = { onEdit(d.id) }, colors = CardDefaults.cardColors(containerColor = PanelLight)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val cover = d.main.firstOrNull { CardRepository.get(it)?.isMonster == true } ?: d.main.firstOrNull() ?: 0
                            CardImage(cover, Modifier.width(44.dp).aspectRatio(0.686f))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Main ${d.main.size} · Extra ${d.extra.size}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                if (probs.isNotEmpty()) Text("⚠ ${probs.first()}", style = MaterialTheme.typography.bodySmall, color = OppColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Opzioni") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Modifica") }, onClick = { menu = false; onEdit(d.id) })
                                    DropdownMenuItem(text = { Text("Duplica") }, onClick = {
                                        menu = false; DeckStore.duplicate(d.id); info = "Creata una copia di «${d.name}»"
                                    })
                                    DropdownMenuItem(text = { Text("Rinomina") }, onClick = { menu = false; renaming = d })
                                    DropdownMenuItem(text = { Text("Copia lista (.ydk)") }, onClick = {
                                        menu = false; clipboard.setText(AnnotatedString(DeckStore.toYdk(d))); info = "Lista copiata negli appunti"
                                    })
                                    DropdownMenuItem(text = { Text("Elimina", color = OppColor) }, onClick = { menu = false; deleting = d })
                                }
                            }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = {
                val d = Deck(name = "Nuovo mazzo")
                DeckStore.upsert(d)
                onEdit(d.id)
            },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("Nuovo mazzo") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        )
    }

    if (importing) {
        var name by remember { mutableStateOf("Mazzo importato") }
        var text by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { importing = false },
            title = { Text("Importa lista .ydk") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Nome del mazzo") }, singleLine = true)
                    OutlinedTextField(text, { text = it }, label = { Text("Incolla qui il contenuto del file .ydk") },
                        modifier = Modifier.height(200.dp))
                    err?.let { Text(it, color = OppColor) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val d = DeckStore.parseYdk(name.ifBlank { "Mazzo importato" }, text)
                    if (d == null) err = "Nessuna carta trovata nel testo."
                    else { DeckStore.upsert(d); importing = false; info = "Importato «${d.name}»" }
                }) { Text("Importa") }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { Text("Annulla") } }
        )
    }
    renaming?.let { d ->
        var name by remember(d.id) { mutableStateOf(d.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rinomina mazzo") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { DeckStore.upsert(d.copy(name = name.ifBlank { d.name })); renaming = null }) { Text("Salva") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Annulla") } }
        )
    }
    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Eliminare «${d.name}»?") },
            text = { Text("Il mazzo verrà cancellato dall'archivio.") },
            confirmButton = { TextButton(onClick = { DeckStore.delete(d.id); deleting = null }) { Text("Elimina", color = OppColor) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annulla") } }
        )
    }
}

// ------------------------------------------------------------------ editor
private enum class SearchMode(val label: String) { NAME("Nome"), ID("Codice ID"), SET("Codice stampa") }

private fun sortCards(ids: List<Int>): List<Int> = ids.sortedWith(compareBy<Int>(
    { val c = CardRepository.get(it); if (c == null) 3 else if (c.isMonster) 0 else if (c.isSpell) 1 else 2 },
    { -(CardRepository.get(it)?.level ?: 0) },
    { CardRepository.name(it) }
))

@Composable
fun DeckEditorScreen(deckId: String, onBack: () -> Unit) {
    val start = remember(deckId) { DeckStore.get(deckId) }
    if (start == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var deck by remember(deckId) { mutableStateOf(start) }
    var tab by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<Int?>(null) }

    fun save(d: Deck) { deck = d; DeckStore.upsert(d) }
    fun copies(id: Int): Int {
        val base = CardRepository.get(id)?.baseId ?: id
        return (deck.main + deck.extra).count { (CardRepository.get(it)?.baseId ?: it) == base }
    }
    fun add(c: CardInfo) {
        val id = CardRepository.playableCode(c.id)
        when {
            copies(id) >= 3 -> message = "Hai già 3 copie di «${c.name}»."
            c.isExtra && deck.extra.size >= 15 -> message = "L'Extra Deck è pieno (15)."
            !c.isExtra && deck.main.size >= 60 -> message = "Il Main Deck è pieno (60)."
            c.isExtra -> { save(deck.copy(extra = deck.extra + id)); message = "Aggiunta all'Extra: ${c.name}" }
            else -> { save(deck.copy(main = deck.main + id)); message = "Aggiunta al Main: ${c.name}" }
        }
    }
    fun removeOne(id: Int) {
        val c = CardRepository.get(id)
        if (c?.isExtra == true || id in deck.extra && id !in deck.main) {
            val l = deck.extra.toMutableList(); l.remove(id); save(deck.copy(extra = l))
        } else {
            val l = deck.main.toMutableList(); l.remove(id); save(deck.copy(main = l))
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Modifica mazzo", onBack)
        OutlinedTextField(
            value = deck.name,
            onValueChange = { save(deck.copy(name = it)) },
            label = { Text("Nome del mazzo") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        )
        val probs = DeckStore.problems(deck)
        Text(
            if (probs.isEmpty()) "✓ Mazzo valido" else "⚠ " + probs.joinToString(" "),
            color = if (probs.isEmpty()) Color(0xFF7BD88F) else OppColor,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 14.dp),
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Cerca carte") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Main (${deck.main.size})") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Extra (${deck.extra.size})") })
        }
        message?.let {
            Text(it, color = Gold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        }
        when (tab) {
            0 -> SearchPanel(onAdd = { add(it) }, onDetail = { detail = it }, copies = { copies(it) })
            1 -> DeckGrid(sortCards(deck.main), onTap = { detail = it })
            else -> DeckGrid(sortCards(deck.extra), onTap = { detail = it })
        }
    }

    detail?.let { id ->
        val c = CardRepository.get(id)
        CardDetailDialog(id, onDismiss = { detail = null }) {
            Row {
                if (id in deck.main || id in deck.extra) TextButton(onClick = { removeOne(id) }) { Text("Togli 1") }
                if (c != null && !c.isToken) TextButton(onClick = { add(c) }) { Text("Aggiungi 1 (${copies(id)})") }
            }
        }
    }
}

@Composable
private fun DeckGrid(ids: List<Int>, onTap: (Int) -> Unit) {
    if (ids.isEmpty()) {
        Text("Vuoto. Aggiungi carte dalla scheda «Cerca carte».", Modifier.padding(20.dp), color = Color.Gray)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(ids) { _, id ->
            CardImage(id, Modifier.aspectRatio(0.686f).clickable { onTap(id) })
        }
    }
}

@Composable
private fun SearchPanel(onAdd: (CardInfo) -> Unit, onDetail: (Int) -> Unit, copies: (Int) -> Int) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(SearchMode.NAME) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CardInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    fun runSetSearch() {
        if (query.isBlank()) return
        loading = true; note = null
        scope.launch {
            results = CardRepository.searchBySetCode(query)
            loading = false
            if (results.isEmpty()) note = "Nessuna carta trovata per «$query». Serve la connessione a internet."
        }
    }

    // ricerca mentre scrivi (nome e ID); il codice di stampa si cerca online col tasto
    LaunchedEffect(query, mode) {
        if (mode == SearchMode.SET) return@LaunchedEffect
        note = null
        if (query.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(250)
        results = withContext(Dispatchers.IO) { CardRepository.search(query) }
        if (results.isEmpty()) note = "Nessun risultato."
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SearchMode.entries.forEach { m ->
                FilterChip(selected = mode == m, onClick = { mode = m; results = emptyList() }, label = { Text(m.label) })
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = {
                Text(when (mode) {
                    SearchMode.NAME -> "Nome della carta (italiano o inglese)"
                    SearchMode.ID -> "Numero in basso a sinistra (es. 89631139)"
                    SearchMode.SET -> "Codice di stampa (es. LOB-IT001)"
                })
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = if (mode == SearchMode.ID) KeyboardType.Number else KeyboardType.Text,
                imeAction = ImeAction.Search
            ),
            keyboardActions = KeyboardActions(onSearch = { if (mode == SearchMode.SET) runSetSearch() }),
            trailingIcon = {
                if (mode == SearchMode.SET) IconButton(onClick = { runSetSearch() }) { Icon(Icons.Default.Search, "Cerca") }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        )
        if (loading) CircularProgressIndicator(Modifier.padding(16.dp).align(Alignment.CenterHorizontally))
        note?.let { Text(it, Modifier.padding(12.dp), color = Color.Gray) }
        LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(results, key = { it.id }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { onDetail(c.id) }.padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CardImage(c.id, Modifier.width(42.dp).aspectRatio(0.686f))
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(c.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Texts.typeLine(c), style = MaterialTheme.typography.labelSmall, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val st = Texts.statLine(c)
                        if (st.isNotEmpty()) Text(st, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                    val n = copies(c.id)
                    if (n > 0) Text("×$n", color = Gold, modifier = Modifier.padding(end = 4.dp))
                    IconButton(onClick = { onAdd(c) }, enabled = (c.type and Types.TOKEN) == 0L) {
                        Icon(Icons.Default.Add, "Aggiungi")
                    }
                }
            }
        }
    }
}
