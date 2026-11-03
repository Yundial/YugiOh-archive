package com.yundial.ygotester.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Deck(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val main: List<Int> = emptyList(),
    val extra: List<Int> = emptyList(),
    val updated: Long = System.currentTimeMillis(),
)

/** Mazzo dell'avversario virtuale (assets/bot_decks.json) */
data class BotDeck(val id: String, val name: String, val level: Int, val desc: String, val main: List<Int>, val extra: List<Int>)

/** Archivio dei mazzi salvato in un file JSON nella memoria dell'app. */
object DeckStore {
    private lateinit var file: File
    val decks = mutableStateListOf<Deck>()
    val botDecks = ArrayList<BotDeck>()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "decks.json")
        load()
        try {
            val arr = JSONArray(ctx.assets.open("bot_decks.json").bufferedReader().readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                botDecks.add(BotDeck(o.getString("id"), o.getString("name"), o.getInt("level"), o.getString("desc"),
                    ints(o.getJSONArray("main")), ints(o.getJSONArray("extra"))))
            }
        } catch (_: Exception) {}
    }

    private fun ints(a: JSONArray) = List(a.length()) { a.getInt(it) }

    private fun load() {
        decks.clear()
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                decks.add(Deck(o.getString("id"), o.getString("name"), ints(o.getJSONArray("main")),
                    ints(o.getJSONArray("extra")), o.optLong("updated")))
            }
        } catch (_: Exception) {}
    }

    private fun save() {
        val arr = JSONArray()
        for (d in decks) {
            arr.put(JSONObject().apply {
                put("id", d.id); put("name", d.name); put("updated", d.updated)
                put("main", JSONArray(d.main)); put("extra", JSONArray(d.extra))
            })
        }
        val tmp = File(file.parentFile, "decks.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    fun get(id: String) = decks.firstOrNull { it.id == id }

    fun upsert(d: Deck) {
        val nd = d.copy(updated = System.currentTimeMillis())
        val i = decks.indexOfFirst { it.id == d.id }
        if (i >= 0) decks[i] = nd else decks.add(0, nd)
        save()
    }

    fun delete(id: String) {
        decks.removeAll { it.id == id }
        save()
    }

    fun duplicate(id: String): Deck? {
        val d = get(id) ?: return null
        val copy = d.copy(id = UUID.randomUUID().toString(), name = d.name + " (copia)", updated = System.currentTimeMillis())
        decks.add(0, copy)
        save()
        return copy
    }

    /** Importa una lista in formato .ydk (EDOPro / YGOPRODeck) */
    fun parseYdk(name: String, text: String): Deck? {
        val main = ArrayList<Int>(); val extra = ArrayList<Int>()
        var sec = 0
        for (raw in text.lines()) {
            val l = raw.trim()
            when {
                l.startsWith("#main") -> sec = 0
                l.startsWith("#extra") -> sec = 1
                l.startsWith("!side") -> sec = 2
                l.isNotEmpty() && l.all { it.isDigit() } -> {
                    val id = CardRepository.playableCode(l.toInt())
                    when (sec) { 0 -> main.add(id); 1 -> extra.add(id) }
                }
            }
        }
        if (main.isEmpty() && extra.isEmpty()) return null
        return Deck(name = name, main = main, extra = extra)
    }

    fun toYdk(d: Deck): String = buildString {
        append("#created by YGO Tester\n#main\n")
        d.main.forEach { append(it).append('\n') }
        append("#extra\n")
        d.extra.forEach { append(it).append('\n') }
        append("!side\n")
    }

    /** Problemi del mazzo per il regolamento base (40-60 Main, max 15 Extra, max 3 copie) */
    fun problems(d: Deck): List<String> {
        val out = ArrayList<String>()
        if (d.main.size < 40) out.add("Il Main Deck ha ${d.main.size} carte: ne servono almeno 40.")
        if (d.main.size > 60) out.add("Il Main Deck ha ${d.main.size} carte: massimo 60.")
        if (d.extra.size > 15) out.add("L'Extra Deck ha ${d.extra.size} carte: massimo 15.")
        val counts = HashMap<Int, Int>()
        for (id in d.main + d.extra) {
            val base = CardRepository.get(id)?.baseId ?: id
            counts[base] = (counts[base] ?: 0) + 1
        }
        for ((id, n) in counts) if (n > 3) out.add("«${CardRepository.name(id)}» compare $n volte (massimo 3).")
        return out
    }
}
