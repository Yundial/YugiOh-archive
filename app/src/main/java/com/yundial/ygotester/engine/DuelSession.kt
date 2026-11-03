package com.yundial.ygotester.engine

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yundial.ygotester.data.CardRepository
import com.yundial.ygotester.data.Texts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Carta sul campo / in mano / nelle pile, come la vede il giocatore (code = 0 se coperta) */
data class FieldCard(
    val code: Int, val pos: Int, val atk: Int, val def: Int, val level: Int, val type: Long,
    val overlays: Int, val baseAtk: Int,
)

data class PlayerView(
    val lp: Int, val deck: Int, val extraCount: Int,
    val hand: List<FieldCard?>, val m: List<FieldCard?>, val s: List<FieldCard?>,
    val gy: List<FieldCard?>, val ban: List<FieldCard?>, val ex: List<FieldCard?>,
)

data class DuelView(
    val turn: Int, val turnPlayer: Int, val phase: Int,
    val chain: List<Pair<Int, Int>>,
    val players: List<PlayerView>,
    val prompt: JSONObject?,
    val winner: Int?, val winReason: Int,
    val error: String?,
)

/** Riferimento a una carta dentro una richiesta di scelta */
data class PCard(val code: Int, val pl: Int, val loc: Int, val seq: Int, val pos: Int, val desc: Long, val param: Int)

fun JSONObject.pcards(key: String): List<PCard> {
    val a = optJSONArray(key) ?: return emptyList()
    return List(a.length()) { i ->
        val o = a.getJSONObject(i)
        PCard(o.optInt("c"), o.optInt("pl"), o.optInt("l"), o.optInt("s"), o.optInt("pos"), o.optLong("d"), o.optInt("x"))
    }
}

/** Costruisce la risposta JSON per il motore */
fun response(a: Int = 0, b: Int = 0, idx: List<Int>? = null, places: List<Int>? = null, mask: Long = 0): String {
    val o = JSONObject()
    o.put("a", a); o.put("b", b)
    if (idx != null) o.put("idx", JSONArray(idx))
    if (places != null) o.put("places", JSONArray(places))
    o.put("mask", mask)
    return o.toString()
}

class DuelSession(private val handle: Long) {
    var view by mutableStateOf<DuelView?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    val log = mutableStateListOf<String>()
    private val mutex = Mutex()
    private var closed = false

    fun start(scope: CoroutineScope) = run(scope) { NativeEngine.advance(handle) }
    fun respond(scope: CoroutineScope, json: String) = run(scope) { NativeEngine.respond(handle, json) }
    fun surrender(scope: CoroutineScope) = run(scope) { NativeEngine.surrender(handle) }

    private fun run(scope: CoroutineScope, call: () -> String) {
        if (busy || closed) return
        busy = true
        scope.launch {
            val json = withContext(Dispatchers.Default) {
                mutex.withLock { if (closed) "{}" else call() }
            }
            applyView(json)
            busy = false
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    fun close() {
        if (closed) return
        closed = true
        // il motore va distrutto fuori dal thread principale, dopo eventuali chiamate in corso
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Default) {
            mutex.withLock { NativeEngine.destroy(handle) }
        }
    }

    private fun card(o: JSONObject?): FieldCard? {
        if (o == null) return null
        return FieldCard(o.optInt("c"), o.optInt("p"), o.optInt("a"), o.optInt("df"), o.optInt("lv"),
            o.optLong("t"), o.optInt("o"), o.optInt("ba"))
    }

    private fun cards(a: JSONArray?): List<FieldCard?> {
        if (a == null) return emptyList()
        return List(a.length()) { i -> if (a.isNull(i)) null else card(a.optJSONObject(i)) }
    }

    private fun applyView(json: String) {
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        val st = o.optJSONObject("state") ?: return
        val pls = st.optJSONArray("p") ?: JSONArray()
        val players = List(pls.length()) { i ->
            val p = pls.getJSONObject(i)
            PlayerView(p.optInt("lp"), p.optInt("deck"), p.optInt("xc"), cards(p.optJSONArray("hand")),
                cards(p.optJSONArray("m")), cards(p.optJSONArray("s")), cards(p.optJSONArray("gy")),
                cards(p.optJSONArray("ban")), cards(p.optJSONArray("ex")))
        }
        val ch = st.optJSONArray("chain") ?: JSONArray()
        val chain = List(ch.length()) { i -> ch.getJSONObject(i).let { it.optInt("c") to it.optInt("pl") } }
        val end = o.optJSONObject("end")
        val logArr = o.optJSONArray("log") ?: JSONArray()
        for (i in 0 until logArr.length()) {
            formatLog(logArr.getJSONObject(i))?.let { log.add(it) }
        }
        while (log.size > 300) log.removeAt(0)
        view = DuelView(
            turn = st.optInt("turn"), turnPlayer = st.optInt("tp"), phase = st.optInt("phase"),
            chain = chain, players = players, prompt = o.optJSONObject("prompt"),
            winner = end?.optInt("winner"), winReason = end?.optInt("reason") ?: 0,
            error = if (o.has("error")) o.optString("error") else null,
        )
    }

    companion object {
        private fun who(pl: Int) = if (pl == 0) "Tu" else "L'avversario"
        private fun n(code: Int) = if (code == 0) "una carta coperta" else "«${CardRepository.name(code)}»"

        fun formatLog(e: JSONObject): String? {
            val pl = e.optInt("pl")
            val c = e.optInt("c")
            return when (e.optString("k")) {
                "turn" -> "── Turno ${e.optInt("n")}: " + (if (pl == 0) "tocca a te" else "tocca all'avversario") + " ──"
                "phase" -> null
                "draw" -> {
                    val cs = e.optJSONArray("c")
                    val num = e.optInt("n")
                    if (pl == 0 && cs != null && cs.length() > 0)
                        "Peschi " + List(cs.length()) { n(cs.getInt(it)) }.joinToString(", ")
                    else "${who(pl)} pesca $num ${if (num == 1) "carta" else "carte"}"
                }
                "summon" -> "${who(pl)} Evoca Normalmente ${n(c)}"
                "spsummon" -> "${who(pl)} Evoca Specialmente ${n(c)}"
                "flip" -> "${who(pl)} Evoca di Scoperta ${n(c)}"
                "set" -> if (c != 0) "${who(pl)} posizioni ${n(c)}" else "${who(pl)} posiziona una carta coperta"
                "chain" -> {
                    val d = e.optLong("d")
                    val eff = if (d >= 10000) CardRepository.effectString((d shr 20).toInt(), (d and 0xfffff).toInt()) else null
                    "${who(pl)} attiva ${n(c)}" + (if (e.optInt("n") > 1) " (anello ${e.optInt("n")})" else "") +
                        (if (eff != null) " – $eff" else "")
                }
                "negated" -> "L'anello ${e.optInt("n")} della catena è stato negato"
                "attack" -> {
                    val t = e.optInt("t")
                    if (e.optBoolean("direct")) "${n(c)} attacca direttamente"
                    else "${n(c)} attacca ${if (t != 0) n(t) else "un mostro coperto"}"
                }
                "dmg" -> "${who(pl)} ${if (pl == 0) "subisci" else "subisce"} ${e.optInt("v")} danni"
                "rec" -> "${who(pl)} ${if (pl == 0) "guadagni" else "guadagna"} ${e.optInt("v")} LP"
                "cost" -> "${who(pl)} ${if (pl == 0) "paghi" else "paga"} ${e.optInt("v")} LP"
                "move" -> {
                    val to = e.optInt("to")
                    val from = e.optInt("from")
                    val nm = n(c)
                    when (to) {
                        0x10 -> "$nm va al Cimitero" + if (from == 0x01) " dal Deck" else ""
                        0x20 -> "$nm viene bandita"
                        0x02 -> if (c == 0) "${who(pl)} aggiunge una carta alla mano" else "$nm va in mano a ${if (pl == 0) "te" else "l'avversario"}"
                        0x01 -> "$nm torna nel Deck"
                        0x40 -> "$nm torna nell'Extra Deck"
                        else -> null
                    }
                }
                "reveal" -> {
                    val cs = e.optJSONArray("c") ?: return null
                    "Carte rivelate: " + List(cs.length()) { n(cs.getInt(it)) }.joinToString(", ")
                }
                "coin" -> {
                    val r = e.optJSONArray("r") ?: return null
                    "Lancio della moneta: " + List(r.length()) { if (r.getInt(it) == 1) "testa" else "croce" }.joinToString(", ")
                }
                "dice" -> {
                    val r = e.optJSONArray("r") ?: return null
                    "Lancio del dado: " + List(r.length()) { r.getInt(it).toString() }.joinToString(", ")
                }
                else -> null
            }
        }

        fun phaseName(p: Int) = Texts.phase(p)
    }
}
