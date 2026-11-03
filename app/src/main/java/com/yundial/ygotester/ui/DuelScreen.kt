@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.yundial.ygotester.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yundial.ygotester.data.CardRepository
import com.yundial.ygotester.data.DeckStore
import com.yundial.ygotester.data.Loc
import com.yundial.ygotester.data.Pos
import com.yundial.ygotester.data.Texts
import com.yundial.ygotester.engine.DuelSession
import com.yundial.ygotester.engine.DuelView
import com.yundial.ygotester.engine.FieldCard
import com.yundial.ygotester.engine.NativeEngine
import com.yundial.ygotester.engine.PCard
import com.yundial.ygotester.engine.PlayerView
import com.yundial.ygotester.engine.pcards
import com.yundial.ygotester.engine.response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.random.Random

/** Un'azione disponibile in Main Phase / Battle Phase */
data class UiAction(val card: PCard?, val label: String, val json: String)

private fun idleActions(p: JSONObject): List<UiAction> {
    val out = ArrayList<UiAction>()
    when (p.optString("k")) {
        "idle" -> {
            p.pcards("summon").forEachIndexed { i, c -> out.add(UiAction(c, "Evoca Normalmente", response(0, i))) }
            p.pcards("sp").forEachIndexed { i, c -> out.add(UiAction(c, "Evoca Specialmente", response(1, i))) }
            p.pcards("repos").forEachIndexed { i, c -> out.add(UiAction(c, "Cambia posizione", response(2, i))) }
            p.pcards("mset").forEachIndexed { i, c -> out.add(UiAction(c, "Posiziona coperto", response(3, i))) }
            p.pcards("sset").forEachIndexed { i, c -> out.add(UiAction(c, "Posiziona coperta", response(4, i))) }
            p.pcards("act").forEachIndexed { i, c ->
                val d = Texts.desc(c.desc)
                out.add(UiAction(c, if (c.desc == 0L || d.isEmpty()) "Attiva" else "Attiva: $d", response(5, i)))
            }
        }
        "battle" -> {
            p.pcards("act").forEachIndexed { i, c ->
                val d = Texts.desc(c.desc)
                out.add(UiAction(c, if (c.desc == 0L || d.isEmpty()) "Attiva" else "Attiva: $d", response(0, i)))
            }
            p.pcards("atk").forEachIndexed { i, c ->
                out.add(UiAction(c, if (c.param != 0) "Attacca (anche direttamente)" else "Attacca", response(1, i)))
            }
        }
    }
    return out
}

private const val PHASE_TIMINGS = 0x1L or 0x2L or 0x4L or 0x8L or 0x10L or 0x20L or 0x1000000L or 0x4000000L

/** Catene "vuote": nessun evento a cui rispondere e solo carte piazzate attivabili (es. una Magia Rapida coperta) */
private fun shouldAutoPass(p: JSONObject, v: DuelView): Boolean {
    if (p.optString("k") != "chain" || p.optBoolean("forced")) return false
    if (v.chain.isNotEmpty()) return false
    val events = (p.optLong("t1") or p.optLong("t2")) and PHASE_TIMINGS.inv()
    if (events != 0L) return false
    if (v.turnPlayer == 1 && v.phase == 0x200) return false // End Phase avversaria: momento classico per le Rapide
    val cards = p.pcards("cards")
    return cards.isNotEmpty() && cards.all { (it.loc and 0x7f) == Loc.SZONE }
}

/** Zona scelta in automatico (la più centrale libera dal proprio lato) */
private fun autoZone(p: JSONObject): String? {
    if (p.optString("k") != "place" || p.optBoolean("dis") || p.optInt("count") > 1) return null
    val z = p.optJSONArray("zones") ?: return null
    val list = List(z.length()) { i -> z.getJSONObject(i) }
    val order = listOf(2, 1, 3, 0, 4, 5, 6, 7)
    val pick = order.firstNotNullOfOrNull { seq -> list.firstOrNull { it.optInt("pl") == 0 && it.optInt("s") == seq } }
        ?: list.firstOrNull() ?: return null
    return response(places = listOf(pick.optInt("pl"), pick.optInt("l"), pick.optInt("s")))
}

private fun matches(c: PCard?, pl: Int, loc: Int, seq: Int) = c != null && c.pl == pl && (c.loc and 0x7f) == loc && c.seq == seq

@Composable
fun DuelScreen(config: DuelConfig, onExit: () -> Unit, onHome: () -> Unit, onRematch: (DuelConfig) -> Unit) {
    val scope = rememberCoroutineScope()
    var session by remember(config) { mutableStateOf<DuelSession?>(null) }
    var prep by remember(config) { mutableStateOf("Preparo il duello…") }
    var prepError by remember(config) { mutableStateOf<String?>(null) }
    var botName by remember(config) { mutableStateOf("Avversario") }
    var askLeave by remember { mutableStateOf(false) }

    LaunchedEffect(config) {
        val mine = DeckStore.get(config.deckId)
        if (mine == null) { prepError = "Mazzo non trovato."; return@LaunchedEffect }
        val pool = DeckStore.botDecks.filter { it.level == config.difficulty }.ifEmpty { DeckStore.botDecks }
        val bot = DeckStore.botDecks.firstOrNull { it.id == config.botDeckId } ?: pool.random()
        botName = bot.name
        val hMain = mine.main.map { CardRepository.playableCode(it) }
        val hExtra = mine.extra.map { CardRepository.playableCode(it) }
        prep = "Controllo gli effetti delle carte…"
        CardRepository.prefetchScripts(hMain + hExtra + bot.main + bot.extra) { done, total ->
            if (total > 0) prep = "Scarico gli effetti delle carte… $done/$total"
        }
        val humanFirst = when (config.first) { 1 -> true; 2 -> false; else -> Random.nextBoolean() }
        prep = if (humanFirst) "Inizi tu!" else "Inizia l'avversario!"
        val handle = withContext(Dispatchers.Default) {
            NativeEngine.create(
                CardRepository.scriptsDir.path, hMain.toIntArray(), hExtra.toIntArray(),
                bot.main.toIntArray(), bot.extra.toIntArray(), config.difficulty, humanFirst, Random.nextLong()
            )
        }
        if (handle == 0L) { prepError = NativeEngine.lastError().ifEmpty { "Impossibile avviare il duello." }; return@LaunchedEffect }
        val s = DuelSession(handle)
        session = s
        s.start(scope)
    }
    DisposableEffect(config) { onDispose { session?.close() } }

    BackHandler { askLeave = true }

    val s = session
    val v = s?.view
    if (s == null || v == null) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (prepError != null) {
                Text(prepError ?: "", color = OppColor, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onExit) { Text("Indietro") }
            } else {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(prep, textAlign = TextAlign.Center)
            }
        }
    } else {
        DuelBoard(v, s, botName, config.difficulty, onLeave = { askLeave = true })
        if (v.winner != null) {
            val w = v.winner
            AlertDialog(
                onDismissRequest = {},
                title = { Text(when (w) { 0 -> "Hai vinto! 🏆"; 1 -> "Hai perso"; else -> "Pareggio" }, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        (v.error?.let { "$it\n" } ?: "") +
                            "Turni giocati: ${v.turn} · LP finali: tu ${v.players.getOrNull(0)?.lp ?: 0}, avversario ${v.players.getOrNull(1)?.lp ?: 0}"
                    )
                },
                confirmButton = { TextButton(onClick = { onRematch(config) }) { Text("Rivincita") } },
                dismissButton = {
                    Row {
                        TextButton(onClick = onExit) { Text("Cambia") }
                        TextButton(onClick = onHome) { Text("Home") }
                    }
                }
            )
        }
    }

    if (askLeave) {
        AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text("Abbandonare il duello?") },
            text = { Text("La partita verrà considerata persa.") },
            confirmButton = { TextButton(onClick = { askLeave = false; onExit() }) { Text("Abbandona", color = OppColor) } },
            dismissButton = { TextButton(onClick = { askLeave = false }) { Text("Continua") } }
        )
    }
}

// ------------------------------------------------------------------ tavolo di gioco
@Composable
private fun DuelBoard(v: DuelView, s: DuelSession, botName: String, difficulty: Int, onLeave: () -> Unit) {
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<Int?>(null) }
    var actionList by remember { mutableStateOf<List<UiAction>?>(null) }
    var pile by remember { mutableStateOf<Pair<String, List<Pair<FieldCard?, PCardKey>>>?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var minimized by remember(v.prompt) { mutableStateOf(false) }
    var smartChain by remember { mutableStateOf(true) }
    var autoZones by remember { mutableStateOf(true) }
    var showOptions by remember { mutableStateOf(false) }

    val me = v.players.getOrNull(0)
    val opp = v.players.getOrNull(1)
    if (me == null || opp == null) return
    val prompt = v.prompt
    val kind = prompt?.optString("k") ?: ""
    val actions = remember(prompt) { if (prompt != null) idleActions(prompt) else emptyList() }
    fun send(json: String) { s.respond(scope, json) }
    LaunchedEffect(prompt, smartChain, autoZones) {
        if (prompt == null || s.busy || v.error != null) return@LaunchedEffect
        val zone = if (autoZones) autoZone(prompt) else null
        if (smartChain && shouldAutoPass(prompt, v)) send(response(-1))
        else if (zone != null) send(zone)
    }
    fun tapCard(pl: Int, loc: Int, seq: Int, code: Int) {
        val acts = actions.filter { matches(it.card, pl, loc, seq) }
        if (acts.isNotEmpty() && !s.busy) actionList = acts
        else if (code != 0) detail = code
    }
    fun openPile(title: String, pl: Int, loc: Int, cards: List<FieldCard?>) {
        pile = title to cards.mapIndexed { i, c -> c to PCardKey(pl, loc, i) }
    }

    Column(Modifier.fillMaxSize()) {
        // --- avversario
        PlayerBar(botName + " · " + difficultyNames[difficulty], opp.lp, v.turnPlayer == 1, OppColor)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Mano ${opp.hand.size}", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(end = 6.dp))
            opp.hand.take(12).forEachIndexed { i, c ->
                val code = c?.code ?: 0
                CardImage(code, Modifier.width(16.dp).height(23.dp).clickable { tapCard(1, Loc.HAND, i, code) })
            }
        }
        // --- campo
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            val cell = (maxWidth - 3.dp * 6) / 7
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                // riga Magie/Trappole avversario (specchiata)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    PileCell(cell, "Deck", opp.deck, null) { }
                    for (i in 4 downTo 0) ZoneCell(cell, opp.s.getOrNull(i), false, actions.any { matches(it.card, 1, Loc.SZONE, i) }) { tapCard(1, Loc.SZONE, i, opp.s.getOrNull(i)?.code ?: 0) }
                    PileCell(cell, "Extra", opp.extraCount, opp.ex.lastOrNull()) { openPile("Extra Deck avversario (scoperte)", 1, Loc.EXTRA, opp.ex) }
                }
                // riga mostri avversario
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    PileCell(cell, "Cimit.", opp.gy.size, opp.gy.lastOrNull()) { openPile("Cimitero avversario", 1, Loc.GRAVE, opp.gy) }
                    for (i in 4 downTo 0) ZoneCell(cell, opp.m.getOrNull(i), true, actions.any { matches(it.card, 1, Loc.MZONE, i) }) { tapCard(1, Loc.MZONE, i, opp.m.getOrNull(i)?.code ?: 0) }
                    ZoneCell(cell, opp.s.getOrNull(5), false, actions.any { matches(it.card, 1, Loc.SZONE, 5) }) { tapCard(1, Loc.SZONE, 5, opp.s.getOrNull(5)?.code ?: 0) }
                }
                // Zone Mostri Extra (condivise)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    PileCell(cell, "Band.", opp.ban.size, opp.ban.lastOrNull()) { openPile("Carte bandite dell'avversario", 1, Loc.REMOVED, opp.ban) }
                    Spacer(Modifier.width(cell))
                    EmzCell(cell, me.m.getOrNull(5), opp.m.getOrNull(6), actions, 5, 6, ::tapCard)
                    Box(Modifier.width(cell), contentAlignment = Alignment.Center) {
                        Text("T${v.turn}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                    EmzCell(cell, me.m.getOrNull(6), opp.m.getOrNull(5), actions, 6, 5, ::tapCard)
                    Spacer(Modifier.width(cell))
                    PileCell(cell, "Band.", me.ban.size, me.ban.lastOrNull()) { openPile("Le tue carte bandite", 0, Loc.REMOVED, me.ban) }
                }
                // riga mostri mia
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    ZoneCell(cell, me.s.getOrNull(5), false, actions.any { matches(it.card, 0, Loc.SZONE, 5) }) { tapCard(0, Loc.SZONE, 5, me.s.getOrNull(5)?.code ?: 0) }
                    for (i in 0..4) ZoneCell(cell, me.m.getOrNull(i), true, actions.any { matches(it.card, 0, Loc.MZONE, i) }) { tapCard(0, Loc.MZONE, i, me.m.getOrNull(i)?.code ?: 0) }
                    PileCell(cell, "Cimit.", me.gy.size, me.gy.lastOrNull(), actions.any { (it.card?.loc ?: 0) == Loc.GRAVE && it.card?.pl == 0 }) { openPile("Il tuo Cimitero", 0, Loc.GRAVE, me.gy) }
                }
                // riga Magie/Trappole mia
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    PileCell(cell, "Extra", me.extraCount, null, actions.any { (it.card?.loc ?: 0) == Loc.EXTRA }) { openPile("Il tuo Extra Deck", 0, Loc.EXTRA, me.ex) }
                    for (i in 0..4) ZoneCell(cell, me.s.getOrNull(i), false, actions.any { matches(it.card, 0, Loc.SZONE, i) }) { tapCard(0, Loc.SZONE, i, me.s.getOrNull(i)?.code ?: 0) }
                    PileCell(cell, "Deck", me.deck, null) { }
                }
            }
        }
        // --- stato e registro
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (v.turnPlayer == 0) "Tuo turno" else "Turno avversario") + " · " + Texts.phase(v.phase) +
                    (if (v.chain.isNotEmpty()) " · Catena: ${v.chain.size}" else ""),
                style = MaterialTheme.typography.labelMedium, color = Gold, modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { showOptions = true }) { Text("Opzioni", fontSize = 12.sp) }
            TextButton(onClick = { showLog = true }) { Text("Registro", fontSize = 12.sp) }
            TextButton(onClick = onLeave) { Text("Esci", color = OppColor, fontSize = 12.sp) }
        }
        if (s.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 8.dp))
            Text("L'avversario sta giocando…", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(horizontal = 8.dp))
        }
        v.error?.let { if (v.winner == null) Text(it, color = OppColor, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp)) }
        LogTicker(s.log, Modifier.weight(1f).fillMaxWidth())
        // --- barra delle azioni
        if (!s.busy && v.winner == null && (kind == "idle" || kind == "battle")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { actionList = actions }, enabled = actions.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    Text("Azioni (${actions.size})", maxLines = 1)
                }
                if (kind == "idle") {
                    if (prompt?.optBoolean("bp") == true) Button(onClick = { send(response(6)) }, modifier = Modifier.weight(1f)) { Text("Battle", maxLines = 1) }
                    if (prompt?.optBoolean("ep") == true) Button(onClick = { send(response(7)) }, modifier = Modifier.weight(1f)) { Text("Fine turno", maxLines = 1) }
                } else {
                    if (prompt?.optBoolean("m2") == true) Button(onClick = { send(response(2)) }, modifier = Modifier.weight(1f)) { Text("Main 2", maxLines = 1) }
                    if (prompt?.optBoolean("ep") == true) Button(onClick = { send(response(3)) }, modifier = Modifier.weight(1f)) { Text("Fine turno", maxLines = 1) }
                }
            }
        } else if (prompt != null && minimized && !s.busy) {
            Button(onClick = { minimized = false }, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)) { Text("Torna alla scelta") }
        }
        // --- la mia mano
        LazyRow(
            Modifier.fillMaxWidth().height(96.dp).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
        ) {
            itemsIndexed(me.hand) { i, c ->
                val code = c?.code ?: 0
                val hasAct = actions.any { matches(it.card, 0, Loc.HAND, i) }
                Box(
                    Modifier.fillMaxHeight().aspectRatio(0.686f)
                        .border(if (hasAct) 2.dp else 0.dp, if (hasAct) Gold else Color.Transparent, RoundedCornerShape(4.dp))
                        .combinedClickable(onClick = { tapCard(0, Loc.HAND, i, code) }, onLongClick = { if (code != 0) detail = code })
                ) { CardImage(code, Modifier.fillMaxSize()) }
            }
        }
        PlayerBar("Tu", me.lp, v.turnPlayer == 0, MineColor)
    }

    // --- richieste di scelta (finestre)
    val autoPassing = prompt != null && v.error == null && ((smartChain && shouldAutoPass(prompt, v)) || (autoZones && autoZone(prompt) != null))
    if (prompt != null && !s.busy && v.winner == null && kind != "idle" && kind != "battle" && !minimized && !autoPassing) {
        PromptDialog(prompt, v, onRespond = { send(it) }, onDetail = { detail = it }, onMinimize = { minimized = true })
    }
    actionList?.let { list ->
        AlertDialog(
            onDismissRequest = { actionList = null },
            title = { Text("Cosa vuoi fare?") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(list) { _, a ->
                        Row(
                            Modifier.fillMaxWidth().clickable { actionList = null; send(a.json) }
                                .background(PanelLight, RoundedCornerShape(6.dp)).padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            a.card?.let { c -> CardImage(c.code, Modifier.width(34.dp).aspectRatio(0.686f)) }
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(a.card?.let { CardRepository.name(it.code) } ?: "", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(a.label, style = MaterialTheme.typography.labelMedium, color = Gold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { actionList = null }) { Text("Annulla") } },
            dismissButton = {
                val first = list.firstOrNull()?.card?.code ?: 0
                if (list.map { it.card?.code }.distinct().size == 1 && first != 0)
                    TextButton(onClick = { actionList = null; detail = first }) { Text("Dettagli") }
            }
        )
    }
    pile?.let { (title, cards) ->
        AlertDialog(
            onDismissRequest = { pile = null },
            title = { Text("$title (${cards.size})") },
            text = {
                if (cards.isEmpty()) Text("Nessuna carta.")
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(cards.reversed()) { _, (fc, key) ->
                        val code = fc?.code ?: 0
                        val acts = actions.filter { matches(it.card, key.pl, key.loc, key.seq) }
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (acts.isNotEmpty() && !s.busy) { pile = null; actionList = acts } else if (code != 0) detail = code
                            }.padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CardImage(code, Modifier.width(34.dp).aspectRatio(0.686f))
                            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                                Text(if (code == 0) "Carta coperta" else CardRepository.name(code), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (acts.isNotEmpty()) Text("Ha un effetto attivabile", style = MaterialTheme.typography.labelSmall, color = Gold)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pile = null }) { Text("Chiudi") } }
        )
    }
    if (showLog) {
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("Registro del duello") },
            text = {
                LazyColumn {
                    itemsIndexed(s.log.reversed()) { _, l -> Text(l, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
                }
            },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text("Chiudi") } }
        )
    }
    detail?.let { CardDetailDialog(it, onDismiss = { detail = null }) }
    if (showOptions) {
        AlertDialog(
            onDismissRequest = { showOptions = false },
            title = { Text("Opzioni del duello") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OptionRow("Salta le catene senza eventi", "Non ti chiede di attivare carte coperte quando non è successo nulla a cui rispondere.", smartChain) { smartChain = it }
                    OptionRow("Scegli le zone in automatico", "Mette le carte nella zona libera più centrale (spegnilo per i mostri Link).", autoZones) { autoZones = it }
                }
            },
            confirmButton = { TextButton(onClick = { showOptions = false }) { Text("Fatto") } }
        )
    }
}

@Composable
private fun OptionRow(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        androidx.compose.material3.Switch(checked = value, onCheckedChange = onChange)
    }
}

data class PCardKey(val pl: Int, val loc: Int, val seq: Int)

@Composable
private fun PlayerBar(name: String, lp: Int, active: Boolean, color: Color) {
    Surface(color = if (active) color.copy(alpha = 0.22f) else Panel) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("LP $lp", style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LogTicker(log: List<String>, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) state.scrollToItem(log.size - 1) }
    LazyColumn(modifier.padding(horizontal = 10.dp), state = state) {
        itemsIndexed(log) { _, l ->
            Text(l, style = MaterialTheme.typography.bodySmall, color = if (l.startsWith("──")) Gold else Color.LightGray, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ZoneCell(size: Dp, card: FieldCard?, monster: Boolean, highlight: Boolean, onTap: () -> Unit) {
    val h = size / 0.686f
    Box(
        Modifier.width(size).height(h)
            .background(Color(0x22FFFFFF), RoundedCornerShape(3.dp))
            .border(if (highlight) 2.dp else 1.dp, if (highlight) Gold else Color(0x33FFFFFF), RoundedCornerShape(3.dp))
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        if (card != null) {
            val faceDown = !Pos.faceUp(card.pos)
            val def = monster && Pos.defense(card.pos)
            val img = Modifier.width(size - 4.dp).height(h - 4.dp)
            Box(if (def) Modifier.rotate(90f).size(width = (size - 4.dp) * 0.75f, height = (h - 4.dp) * 0.75f) else img) {
                CardImage(if (faceDown && card.code != 0) card.code else if (faceDown) 0 else card.code,
                    Modifier.fillMaxSize().alpha(if (faceDown && card.code != 0) 0.55f else 1f))
            }
            if (monster && !faceDown) {
                Text(
                    if (card.type and 0x4000000L != 0L) "${card.atk}" else "${card.atk}/${card.def}",
                    fontSize = 8.sp, color = Color.White, maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomCenter).background(Color(0xAA000000)).padding(horizontal = 2.dp)
                )
            }
            if (card.overlays > 0) {
                Text("${card.overlays}", fontSize = 9.sp, color = Color.Black,
                    modifier = Modifier.align(Alignment.TopEnd).background(Gold, RoundedCornerShape(50)).padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable
private fun EmzCell(size: Dp, mine: FieldCard?, opp: FieldCard?, actions: List<UiAction>, mySeq: Int, oppSeq: Int,
                    tap: (Int, Int, Int, Int) -> Unit) {
    when {
        mine != null -> ZoneCell(size, mine, true, actions.any { matches(it.card, 0, Loc.MZONE, mySeq) }) { tap(0, Loc.MZONE, mySeq, mine.code) }
        opp != null -> ZoneCell(size, opp, true, actions.any { matches(it.card, 1, Loc.MZONE, oppSeq) }) { tap(1, Loc.MZONE, oppSeq, opp.code) }
        else -> ZoneCell(size, null, true, false) { }
    }
}

@Composable
private fun PileCell(size: Dp, label: String, count: Int, top: FieldCard?, highlight: Boolean = false, onTap: () -> Unit) {
    val h = size / 0.686f
    Box(
        Modifier.width(size).height(h)
            .background(Color(0x14FFFFFF), RoundedCornerShape(3.dp))
            .border(if (highlight) 2.dp else 1.dp, if (highlight) Gold else Color(0x22FFFFFF), RoundedCornerShape(3.dp))
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        if (top != null) CardImage(top.code, Modifier.fillMaxSize().padding(2.dp).alpha(0.8f))
        else if (count > 0 && (label == "Deck" || label == "Extra")) CardBack(Modifier.fillMaxSize().padding(2.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(3.dp)).padding(horizontal = 2.dp)) {
            Text(label, fontSize = 8.sp, color = Color.LightGray, maxLines = 1)
            Text("$count", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}
