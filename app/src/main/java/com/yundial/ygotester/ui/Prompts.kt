@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.yundial.ygotester.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yundial.ygotester.data.CardInfo
import com.yundial.ygotester.data.CardRepository
import com.yundial.ygotester.data.Loc
import com.yundial.ygotester.data.Pos
import com.yundial.ygotester.data.Texts
import com.yundial.ygotester.engine.DuelView
import com.yundial.ygotester.engine.PCard
import com.yundial.ygotester.engine.pcards
import com.yundial.ygotester.engine.response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

private fun whereLabel(c: PCard): String {
    val who = if (c.pl == 0) "Tuo" else "Avv."
    val loc = c.loc and 0x7f
    val where = when (loc) {
        Loc.MZONE -> if (c.seq >= 5) "Zona Extra" else "Mostri"
        Loc.SZONE -> if (c.seq == 5) "Terreno" else "M/T"
        Loc.HAND -> "Mano"
        Loc.GRAVE -> "Cimitero"
        Loc.REMOVED -> "Bandita"
        Loc.DECK -> "Deck"
        Loc.EXTRA -> "Extra"
        else -> ""
    }
    return if (c.loc and Loc.OVERLAY != 0) "$who · Materiale" else "$who · $where"
}

private fun hintText(p: JSONObject, fallback: String): String {
    val h = p.optLong("hint")
    if (h == 0L) return fallback
    val t = Texts.desc(h)
    return t.ifEmpty { fallback }
}

/** Finestra di scelta per tutte le richieste del motore diverse da Main/Battle Phase */
@Composable
fun PromptDialog(p: JSONObject, v: DuelView, onRespond: (String) -> Unit, onDetail: (Int) -> Unit, onMinimize: () -> Unit) {
    when (p.optString("k")) {
        "effyn" -> {
            val c = p.optJSONObject("card")
            val code = c?.optInt("c") ?: 0
            val d = p.optLong("d")
            SimpleDialog(
                title = "Attivare l'effetto?",
                onMinimize = onMinimize,
                body = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CardImage(code, Modifier.width(56.dp).aspectRatio(0.686f).clickable { if (code != 0) onDetail(code) })
                        Column(Modifier.padding(start = 10.dp)) {
                            Text("«${CardRepository.name(code)}»", fontWeight = FontWeight.SemiBold)
                            if (d != 0L) Text(Texts.desc(d, code), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                buttons = listOf("Sì" to { onRespond(response(1)) }, "No" to { onRespond(response(0)) })
            )
        }
        "yesno" -> {
            val d = p.optLong("d")
            val text = if (d == 31L) "Vuoi attaccare direttamente?" else Texts.desc(d).ifEmpty { "Confermi?" }
            SimpleDialog(title = "Scelta", onMinimize = onMinimize, body = { Text(text) },
                buttons = listOf("Sì" to { onRespond(response(1)) }, "No" to { onRespond(response(0)) }))
        }
        "option", "number" -> {
            val opts = p.optJSONArray("opts")
            val n = opts?.length() ?: 0
            val isNumber = p.optString("k") == "number"
            SimpleDialog(
                title = if (isNumber) "Dichiara un numero" else hintText(p, "Scegli un'opzione"),
                onMinimize = onMinimize,
                body = {
                    LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(n) { i ->
                            val value = opts!!.optLong(i)
                            OutlinedButton(onClick = { onRespond(response(i)) }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (isNumber) value.toString() else Texts.desc(value), textAlign = TextAlign.Center)
                            }
                        }
                    }
                },
                buttons = emptyList()
            )
        }
        "card", "tribute", "sum" -> {
            val kind = p.optString("k")
            val cards = p.pcards("cards")
            val must = p.pcards("must")
            val min = p.optInt("min")
            val max = p.optInt("max").coerceAtLeast(1)
            val cancel = p.optBoolean("cancel")
            val title = when (kind) {
                "tribute" -> hintText(p, "Scegli i mostri da offrire come Tributo")
                "sum" -> hintText(p, "Scegli le carte") + " (totale ${p.optInt("acc")})"
                else -> hintText(p, "Scegli le carte")
            }
            val sub = when (kind) {
                "sum" -> if (must.isNotEmpty()) "Già incluse: " + must.joinToString { CardRepository.name(it.code) } else "La somma dei valori deve essere ${p.optInt("acc")}"
                else -> if (min == max) "Seleziona $min ${if (min == 1) "carta" else "carte"}" else "Seleziona da $min a $max carte"
            }
            CardPicker(
                title = title, subtitle = sub, cards = cards,
                showValue = kind == "sum" || kind == "tribute",
                canConfirm = { n -> if (kind == "card") n in min.coerceAtLeast(1)..max || (n == 0 && min == 0) else n >= 1 },
                single = kind == "card" && max == 1 && min <= 1,
                cancelLabel = if (cancel) "Annulla" else null,
                onConfirm = { onRespond(response(0, 0, idx = it)) },
                onCancel = { onRespond(response(-1)) },
                onDetail = onDetail, onMinimize = onMinimize
            )
        }
        "unselect" -> {
            val cards = p.pcards("cards")
            val sel = p.pcards("sel")
            SimpleDialog(
                title = hintText(p, "Scegli le carte"),
                onMinimize = onMinimize,
                body = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Tocca una carta per sceglierla (min ${p.optInt("min")}, max ${p.optInt("max")})", style = MaterialTheme.typography.bodySmall)
                        CardGrid(cards, emptySet(), showValue = false, onTap = { i -> onRespond(response(1, i)) }, onDetail = onDetail)
                        if (sel.isNotEmpty()) {
                            Text("Già scelte (tocca per togliere):", style = MaterialTheme.typography.bodySmall, color = Gold)
                            CardGrid(sel, sel.indices.toSet(), showValue = false, onTap = { j -> onRespond(response(1, cards.size + j)) }, onDetail = onDetail)
                        }
                    }
                },
                buttons = buildList {
                    if (p.optBoolean("finish")) add("Fine" to { onRespond(response(-1)) })
                    else if (p.optBoolean("cancel")) add("Annulla" to { onRespond(response(-1)) })
                }
            )
        }
        "chain" -> {
            val cards = p.pcards("cards")
            val forced = p.optBoolean("forced")
            val last = v.chain.lastOrNull()
            SimpleDialog(
                title = if (forced) "Devi attivare un effetto" else "Vuoi attivare una carta o un effetto?",
                onMinimize = onMinimize,
                body = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (last != null) Text("In risposta a «${CardRepository.name(last.first)}» (${if (last.second == 0) "tua" else "dell'avversario"})",
                            style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                        LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            itemsIndexed(cards) { i, c ->
                                Row(
                                    Modifier.fillMaxWidth().background(PanelLight, RoundedCornerShape(6.dp))
                                        .combinedClickable(onClick = { onRespond(response(i)) }, onLongClick = { if (c.code != 0) onDetail(c.code) })
                                        .padding(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CardImage(c.code, Modifier.width(36.dp).aspectRatio(0.686f))
                                    Column(Modifier.padding(start = 8.dp)) {
                                        Text(CardRepository.name(c.code), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val d = Texts.desc(c.desc)
                                        if (d.isNotEmpty()) Text(d, style = MaterialTheme.typography.labelSmall, color = Gold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                        Text(whereLabel(c), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                    }
                                }
                            }
                        }
                    }
                },
                buttons = if (forced) emptyList() else listOf("No" to { onRespond(response(-1)) })
            )
        }
        "place" -> {
            val count = p.optInt("count").coerceAtLeast(1)
            val zones = p.optJSONArray("zones")
            val list = List(zones?.length() ?: 0) { i -> zones!!.getJSONObject(i).let { Triple(it.optInt("pl"), it.optInt("l"), it.optInt("s")) } }
            val chosen = remember(p) { mutableStateListOf<Triple<Int, Int, Int>>() }
            fun send(sel: List<Triple<Int, Int, Int>>) {
                onRespond(response(places = sel.flatMap { listOf(it.first, it.second, it.third) }))
            }
            SimpleDialog(
                title = hintText(p, if (p.optBoolean("dis")) "Scegli le zone da rendere inutilizzabili" else "Scegli la zona"),
                onMinimize = onMinimize,
                body = {
                    LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(list) { _, z ->
                            val on = z in chosen
                            OutlinedButton(
                                onClick = {
                                    if (count == 1) send(listOf(z))
                                    else if (on) chosen.remove(z) else if (chosen.size < count) chosen.add(z)
                                },
                                modifier = Modifier.fillMaxWidth().border(if (on) 2.dp else 0.dp, if (on) Gold else Color.Transparent, RoundedCornerShape(20.dp))
                            ) { Text(Texts.zone(z.first, z.second, z.third)) }
                        }
                    }
                },
                buttons = buildList {
                    add("Automatica" to { send(list.take(count)) })
                    if (count > 1) add("Conferma (${chosen.size}/$count)" to { if (chosen.size == count) send(chosen.toList()) })
                }
            )
        }
        "position" -> {
            val code = p.optInt("c")
            val allowed = p.optInt("pos")
            SimpleDialog(
                title = "Scegli la posizione",
                onMinimize = onMinimize,
                body = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CardImage(code, Modifier.width(60.dp).aspectRatio(0.686f).clickable { if (code != 0) onDetail(code) })
                        Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(CardRepository.name(code), fontWeight = FontWeight.SemiBold)
                            for (pos in listOf(Pos.FACEUP_ATTACK, Pos.FACEUP_DEFENSE, Pos.FACEDOWN_DEFENSE, Pos.FACEDOWN_ATTACK)) {
                                if (allowed and pos != 0) Button(onClick = { onRespond(response(pos)) }, modifier = Modifier.fillMaxWidth()) { Text(Texts.position(pos)) }
                            }
                        }
                    }
                },
                buttons = emptyList()
            )
        }
        "counter" -> {
            val cards = p.pcards("cards")
            val total = p.optInt("count")
            val values = remember(p) { mutableStateListOf<Int>().apply { repeat(cards.size) { add(0) } } }
            SimpleDialog(
                title = "Rimuovi $total segnalini",
                onMinimize = onMinimize,
                body = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        cards.forEachIndexed { i, c ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${CardRepository.name(c.code)} (${c.param})", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                TextButton(onClick = { if (values[i] > 0) values[i] = values[i] - 1 }) { Text("−") }
                                Text("${values[i]}")
                                TextButton(onClick = { if (values[i] < c.param && values.sum() < total) values[i] = values[i] + 1 }) { Text("+") }
                            }
                        }
                    }
                },
                buttons = listOf("Conferma (${values.sum()}/$total)" to { if (values.sum() == total) onRespond(response(idx = values.toList())) })
            )
        }
        "race", "attrib" -> {
            val isRace = p.optString("k") == "race"
            val count = p.optInt("count").coerceAtLeast(1)
            val avail = p.optLong("avail")
            val options: List<Pair<Long, String>> = if (isRace) Texts.allRaces() else Texts.allAttributes().map { it.first.toLong() to it.second }
            val usable = options.filter { avail and it.first != 0L }
            val chosen = remember(p) { mutableStateListOf<Long>() }
            SimpleDialog(
                title = if (isRace) "Dichiara $count Tipo" else "Dichiara $count Attributo",
                onMinimize = onMinimize,
                body = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        usable.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.forEach { (bit, label) ->
                                    FilterChip(
                                        selected = bit in chosen,
                                        onClick = { if (bit in chosen) chosen.remove(bit) else if (chosen.size < count) chosen.add(bit) },
                                        label = { Text(label, fontSize = 11.sp) }
                                    )
                                }
                            }
                        }
                    }
                },
                buttons = listOf("Conferma" to { if (chosen.size == count) onRespond(response(mask = chosen.fold(0L) { a, b -> a or b })) })
            )
        }
        "anncard" -> AnnounceCardDialog(onRespond, onMinimize)
        "rps" -> SimpleDialog(
            title = "Sasso, carta, forbici",
            onMinimize = onMinimize,
            body = { Text("Scegli la tua mano") },
            buttons = listOf("Sasso" to { onRespond(response(1)) }, "Carta" to { onRespond(response(2)) }, "Forbici" to { onRespond(response(3)) })
        )
        else -> SimpleDialog(
            title = "Scelta non supportata",
            onMinimize = onMinimize,
            body = { Text("Il motore ha chiesto una scelta che l'app non sa mostrare. Prova a confermare la scelta predefinita.") },
            buttons = listOf("Predefinita" to { onRespond(response(0)) })
        )
    }
}

@Composable
private fun SimpleDialog(title: String, onMinimize: () -> Unit, body: @Composable () -> Unit, buttons: List<Pair<String, () -> Unit>>) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = body,
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                buttons.forEach { (label, action) -> TextButton(onClick = action) { Text(label) } }
            }
        },
        dismissButton = { TextButton(onClick = onMinimize) { Text("Guarda il campo", color = Color.Gray) } }
    )
}

@Composable
private fun CardGrid(cards: List<PCard>, selected: Set<Int>, showValue: Boolean, onTap: (Int) -> Unit, onDetail: (Int) -> Unit, order: List<Int> = emptyList()) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.heightIn(max = 360.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        itemsIndexed(cards) { i, c ->
            val on = i in selected
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(0.686f)
                        .border(if (on) 3.dp else 0.dp, if (on) Gold else Color.Transparent, RoundedCornerShape(4.dp))
                        .combinedClickable(onClick = { onTap(i) }, onLongClick = { if (c.code != 0) onDetail(c.code) })
                ) {
                    CardImage(c.code, Modifier.fillMaxWidth().aspectRatio(0.686f))
                    if (on && order.isNotEmpty()) {
                        Text("${order.indexOf(i) + 1}", color = Color.Black, fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.TopEnd).background(Gold, RoundedCornerShape(50)).padding(horizontal = 5.dp))
                    }
                }
                Text(if (c.code == 0) "Coperta" else CardRepository.name(c.code), fontSize = 9.sp, maxLines = 2, lineHeight = 10.sp,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(whereLabel(c), fontSize = 8.sp, color = Color.Gray, maxLines = 1)
                if (showValue && c.param != 0) {
                    val o1 = c.param and 0xffff
                    val o2 = c.param shr 16
                    Text(if (o2 != 0 && o2 != o1) "val. $o1/$o2" else "val. $o1", fontSize = 8.sp, color = Gold)
                }
            }
        }
    }
}

@Composable
private fun CardPicker(
    title: String, subtitle: String, cards: List<PCard>, showValue: Boolean,
    canConfirm: (Int) -> Boolean, single: Boolean, cancelLabel: String?,
    onConfirm: (List<Int>) -> Unit, onCancel: () -> Unit, onDetail: (Int) -> Unit, onMinimize: () -> Unit,
) {
    val chosen = remember(cards) { mutableStateListOf<Int>() }
    SimpleDialog(
        title = title,
        onMinimize = onMinimize,
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(subtitle + if (single) "" else " · tieni premuto per i dettagli", style = MaterialTheme.typography.bodySmall)
                CardGrid(
                    cards, chosen.toSet(), showValue,
                    onTap = { i ->
                        if (single) onConfirm(listOf(i))
                        else if (i in chosen) chosen.remove(i) else chosen.add(i)
                    },
                    onDetail = onDetail, order = chosen.toList()
                )
            }
        },
        buttons = buildList {
            if (cancelLabel != null) add(cancelLabel to onCancel)
            if (!single) add("Conferma (${chosen.size})" to { if (canConfirm(chosen.size)) onConfirm(chosen.toList()) })
        }
    )
}

@Composable
private fun AnnounceCardDialog(onRespond: (String) -> Unit, onMinimize: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CardInfo>>(emptyList()) }
    LaunchedEffect(q) {
        if (q.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(250)
        results = withContext(Dispatchers.IO) { CardRepository.search(q, 30) }
    }
    SimpleDialog(
        title = "Dichiara il nome di una carta",
        onMinimize = onMinimize,
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("Cerca per nome") })
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    itemsIndexed(results) { _, c ->
                        Text(
                            c.name,
                            modifier = Modifier.fillMaxWidth().clickable { onRespond(response(c.id)) }.padding(8.dp)
                        )
                    }
                }
                Text("Se la carta non è valida per l'effetto, il gioco te la farà scegliere di nuovo.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        },
        buttons = emptyList()
    )
}
