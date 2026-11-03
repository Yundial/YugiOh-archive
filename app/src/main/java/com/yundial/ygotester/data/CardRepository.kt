package com.yundial.ygotester.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import com.yundial.ygotester.engine.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer

/** Una carta: dati di gioco dal database di EDOPro, testi in italiano da YGOPRODeck. */
class CardInfo(
    val id: Int,
    val alias: Int,
    val name: String,
    val nameEn: String,
    val desc: String,
    val type: Long,
    val atk: Int,
    val def: Int,
    val levelRaw: Long,
    val race: Long,
    val attribute: Int,
) {
    val isMonster get() = type and Types.MONSTER != 0L
    val isSpell get() = type and Types.SPELL != 0L
    val isTrap get() = type and Types.TRAP != 0L
    val isExtra get() = isMonster && (type and Types.EXTRA) != 0L
    val isLink get() = type and Types.LINK != 0L
    val isToken get() = type and Types.TOKEN != 0L
    val level get() = (levelRaw and 0xff).toInt()
    val linkRating get() = if (isLink) def else 0
    /** Nome usato per il limite di 3 copie (le versioni alternative contano come la stessa carta) */
    val baseId get() = if (alias != 0 && kotlin.math.abs(alias - id) < 20) alias else id
}

object CardRepository {
    private lateinit var dir: File
    private var cdb: SQLiteDatabase? = null
    private var itdb: SQLiteDatabase? = null
    private val cache = HashMap<Int, CardInfo>()

    var latestData = false
        private set

    val scriptsDir: File get() = File(dir, "scripts")
    private val cdbFile get() = File(dir, "cards.cdb")
    private val binFile get() = File(dir, "cards.bin")
    private val itFile get() = File(dir, "it.db")
    private val prefsName = "ygotester"

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "data").apply { mkdirs() }
        latestData = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getBoolean("latest", false)
        if (isReady()) open()
    }

    fun isReady() = cdbFile.exists() && binFile.exists() && itFile.exists()

    @Synchronized
    private fun open() {
        cdb?.close(); itdb?.close()
        cdb = SQLiteDatabase.openDatabase(cdbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        itdb = SQLiteDatabase.openDatabase(itFile.path, null, SQLiteDatabase.OPEN_READONLY)
        cache.clear()
        NativeEngine.loadCards(binFile.path)
    }

    // ------------------------------------------------------------------ scaricamento iniziale
    /**
     * Scarica il database delle carte (EDOPro) e i nomi/testi italiani (YGOPRODeck).
     * [onProgress] riceve un messaggio e una frazione 0..1.
     */
    suspend fun downloadAll(ctx: Context, latest: Boolean, onProgress: (String, Float) -> Unit): String? =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            onProgress("Scarico il database delle regole…", 0f)
            val newCdb = File(dir, "cards.cdb.new")
            val ok = Net.download(Net.cdbUrl(latest), newCdb) { done, total ->
                if (total > 0) onProgress("Scarico il database delle regole…", 0.4f * done / total)
            }
            if (!ok) return@withContext "Impossibile scaricare il database delle carte. Controlla la connessione."
            onProgress("Preparo il motore…", 0.42f)
            val newBin = File(dir, "cards.bin.new")
            if (!writeEngineBin(newCdb, newBin)) return@withContext "Database delle carte non valido."
            onProgress("Scarico i nomi italiani…", 0.45f)
            val newIt = File(dir, "it.db.new")
            val err = buildItalianDb(newIt) { f -> onProgress("Scarico i nomi italiani…", 0.45f + 0.5f * f) }
            if (err != null) return@withContext err
            synchronized(this@CardRepository) {
                cdb?.close(); itdb?.close(); cdb = null; itdb = null
                newCdb.renameTo(cdbFile)
                newBin.renameTo(binFile)
                newIt.renameTo(itFile)
                // gli script vanno riscaricati se cambia la versione
                if (latest != latestData) scriptsDir.deleteRecursively()
                latestData = latest
                ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putBoolean("latest", latest).apply()
                open()
            }
            onProgress("Fatto!", 1f)
            null
        }

    /** Converte il .cdb nel formato binario letto dal motore C++ (stesso di tools/gen_cards_bin.py). */
    private fun writeEngineBin(cdbPath: File, out: File): Boolean = try {
        val db = SQLiteDatabase.openDatabase(cdbPath.path, null, SQLiteDatabase.OPEN_READONLY)
        db.rawQuery("select id, alias, setcode, type, level, attribute, race, atk, def, category from datas", null).use { c ->
            val buf = ByteBuffer.allocate(12 + c.count * 52).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("YGTC".toByteArray(Charsets.US_ASCII))
            buf.putInt(1)
            buf.putInt(c.count)
            while (c.moveToNext()) {
                buf.putInt(c.getLong(0).toInt())
                buf.putInt(c.getLong(1).toInt())
                buf.putLong(c.getLong(2))
                buf.putInt(c.getLong(3).toInt())
                buf.putInt(c.getLong(4).toInt())
                buf.putInt(c.getLong(5).toInt())
                buf.putLong(c.getLong(6))
                buf.putInt(c.getLong(7).toInt())
                buf.putInt(c.getLong(8).toInt())
                buf.putLong(c.getLong(9))
            }
            out.writeBytes(buf.array())
        }
        db.close()
        true
    } catch (e: Exception) {
        false
    }

    private fun buildItalianDb(out: File, onProgress: (Float) -> Unit): String? {
        out.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(out, null)
        db.execSQL("create table it(id integer primary key, name text, desc text, norm text)")
        val stmt = db.compileStatement("insert or ignore into it(id, name, desc, norm) values(?,?,?,?)")
        try {
            val resp = Net.client.newCall(Request.Builder().url(Net.ITALIAN_CARDS).build()).execute()
            if (!resp.isSuccessful) { db.close(); return "Il server dei testi italiani non risponde (${resp.code})." }
            val body = resp.body ?: run { db.close(); return "Risposta vuota dal server dei testi italiani." }
            val total = body.contentLength()
            var count = 0
            db.beginTransaction()
            body.byteStream().use { raw ->
                val counting = CountingStream(raw)
                val r = JsonReader(InputStreamReader(counting, Charsets.UTF_8))
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() != "data") { r.skipValue(); continue }
                    r.beginArray()
                    while (r.hasNext()) {
                        var id = 0; var name = ""; var desc = ""
                        val alt = ArrayList<Int>()
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "id" -> id = r.nextInt()
                                "name" -> name = r.nextString()
                                "desc" -> desc = r.nextString()
                                "card_images" -> {
                                    r.beginArray()
                                    while (r.hasNext()) {
                                        r.beginObject()
                                        while (r.hasNext()) {
                                            if (r.nextName() == "id" && r.peek() == JsonToken.NUMBER) alt.add(r.nextInt()) else r.skipValue()
                                        }
                                        r.endObject()
                                    }
                                    r.endArray()
                                }
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                        if (id != 0 && name.isNotEmpty()) {
                            for (cid in listOf(id) + alt) {
                                stmt.bindLong(1, cid.toLong())
                                stmt.bindString(2, name)
                                stmt.bindString(3, desc)
                                stmt.bindString(4, normalize(name))
                                stmt.executeInsert()
                                stmt.clearBindings()
                            }
                            count++
                            if (count % 300 == 0 && total > 0) onProgress(counting.count.toFloat() / total)
                        }
                    }
                    r.endArray()
                }
                r.endObject()
            }
            db.setTransactionSuccessful()
            db.endTransaction()
            db.close()
            return if (count < 1000) "Elenco carte italiane incompleto ($count carte)." else null
        } catch (e: Exception) {
            try { if (db.inTransaction()) db.endTransaction() } catch (_: Exception) {}
            db.close()
            return "Errore nello scaricare i testi italiani: ${e.message}"
        }
    }

    private class CountingStream(private val inner: java.io.InputStream) : java.io.InputStream() {
        var count = 0L
        override fun read(): Int { val b = inner.read(); if (b >= 0) count++; return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int { val n = inner.read(b, off, len); if (n > 0) count += n; return n }
        override fun close() = inner.close()
    }

    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    // ------------------------------------------------------------------ lettura carte
    @Synchronized
    fun get(id: Int): CardInfo? {
        if (id == 0) return null
        cache[id]?.let { return it }
        val db = cdb ?: return null
        val info = db.rawQuery(
            "select d.id, d.alias, d.type, d.atk, d.def, d.level, d.race, d.attribute, t.name, t.desc " +
                "from datas d left join texts t on t.id = d.id where d.id = ?", arrayOf(id.toString())
        ).use { c ->
            if (!c.moveToFirst()) return@use null
            val en = c.getString(8) ?: "#$id"
            var itName: String? = null
            var itDesc: String? = null
            itdb?.rawQuery("select name, desc from it where id = ?", arrayOf(id.toString()))?.use { ic ->
                if (ic.moveToFirst()) { itName = ic.getString(0); itDesc = ic.getString(1) }
            }
            val alias = c.getLong(1).toInt()
            if (itName == null && alias != 0) {
                itdb?.rawQuery("select name, desc from it where id = ?", arrayOf(alias.toString()))?.use { ic ->
                    if (ic.moveToFirst()) { itName = ic.getString(0); itDesc = ic.getString(1) }
                }
            }
            CardInfo(
                id = c.getLong(0).toInt(), alias = alias, name = itName ?: en, nameEn = en,
                desc = itDesc ?: (c.getString(9) ?: ""), type = c.getLong(2), atk = c.getLong(3).toInt(),
                def = c.getLong(4).toInt(), levelRaw = c.getLong(5), race = c.getLong(6), attribute = c.getLong(7).toInt()
            )
        }
        if (info != null) cache[id] = info
        return info
    }

    fun name(id: Int): String = if (id == 0) "carta coperta" else get(id)?.name ?: "#$id"

    /** Testo di un effetto (str1..16 del database, in inglese) */
    @Synchronized
    fun effectString(code: Int, index: Int): String? {
        if (index !in 0..15) return null
        val db = cdb ?: return null
        return db.rawQuery("select str${index + 1} from texts where id = ?", arrayOf(code.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }

    /** Codice da usare in partita: per le carte con errata il motore usa la versione aggiornata (+1..+9) */
    @Synchronized
    fun playableCode(id: Int): Int {
        val db = cdb ?: return id
        db.rawQuery("select id from datas where alias = ? and id > ? and id < ? order by id desc limit 1",
            arrayOf(id.toString(), id.toString(), (id + 10).toString())).use { c ->
            if (c.moveToFirst()) return c.getLong(0).toInt()
        }
        return id
    }

    /** Ricerca per nome (italiano o inglese) oppure per codice ID (le cifre stampate sulla carta) */
    @Synchronized
    fun search(query: String, limit: Int = 80): List<CardInfo> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val digits = q.filter { it.isDigit() }
        val ids = LinkedHashSet<Int>()
        if (digits.length >= 4 && digits.length == q.replace(" ", "").length) {
            val n = digits.toLongOrNull()?.toInt() ?: 0
            if (get(n) != null) ids.add(n)
            cdb?.rawQuery("select id from datas where alias = ? limit 5", arrayOf(n.toString()))?.use { c ->
                while (c.moveToNext()) ids.add(c.getLong(0).toInt())
            }
            cdb?.rawQuery("select id from datas where cast(id as text) like ? limit 20", arrayOf("$digits%"))?.use { c ->
                while (c.moveToNext()) ids.add(c.getLong(0).toInt())
            }
        } else {
            val norm = normalize(q)
            // prima chi inizia con il testo cercato, poi chi lo contiene
            itdb?.rawQuery("select id from it where norm like ? order by length(name) limit $limit", arrayOf("$norm%"))?.use { c ->
                while (c.moveToNext()) ids.add(c.getLong(0).toInt())
            }
            itdb?.rawQuery("select id from it where norm like ? order by length(name) limit $limit", arrayOf("%$norm%"))?.use { c ->
                while (c.moveToNext()) ids.add(c.getLong(0).toInt())
            }
            cdb?.rawQuery("select id from texts where lower(name) like ? order by length(name) limit $limit", arrayOf("%${q.lowercase()}%"))?.use { c ->
                while (c.moveToNext()) ids.add(c.getLong(0).toInt())
            }
        }
        val out = ArrayList<CardInfo>()
        val seenNames = HashSet<String>()
        for (id in ids) {
            val c = get(id) ?: continue
            if (c.isToken) continue
            if (!seenNames.add(c.nameEn)) continue  // evita i doppioni delle illustrazioni alternative
            out.add(c)
            if (out.size >= limit) break
        }
        return out
    }

    /**
     * Ricerca per codice di stampa (es. "LOB-IT001", "SDK-I001", "RA01-EN017").
     * Usa YGOPRODeck: i codici italiani vengono convertiti nella versione inglese equivalente.
     */
    suspend fun searchBySetCode(raw: String): List<CardInfo> = withContext(Dispatchers.IO) {
        val code = raw.trim().uppercase().replace(" ", "")
        val m = Regex("^([A-Z0-9]+)-([A-Z]{0,2})(\\d+)$").find(code)
        val candidates = LinkedHashSet<String>()
        if (m != null) {
            val (set, region, num) = m.destructured
            candidates.add("$set-EN$num")
            if (region.length <= 1) candidates.add("$set-E$num")
            candidates.add("$set-$region$num")
            candidates.add("$set-$num")
        } else candidates.add(code)
        val result = ArrayList<CardInfo>()
        for (cand in candidates) {
            val json = Net.getString(Net.setCodeInfo(cand)) ?: continue
            try {
                val obj = org.json.JSONObject(json)
                if (obj.has("id")) {
                    val id = obj.getInt("id")
                    get(id)?.let { result.add(it) }
                    if (result.isNotEmpty()) break
                }
            } catch (_: Exception) {
                try {
                    val arr = JSONArray(json)
                    for (i in 0 until arr.length()) arr.getJSONObject(i).optInt("id").let { id -> get(id)?.let { result.add(it) } }
                    if (result.isNotEmpty()) break
                } catch (_: Exception) {}
            }
        }
        result
    }

    // ------------------------------------------------------------------ script delle carte
    private val baseScripts = listOf(
        "constant.lua", "utility.lua", "archetype_setcode_constants.lua", "card_counter_constants.lua",
        "cards_specific_functions.lua", "chain.lua", "debug_utility.lua", "deprecated_functions.lua",
        "proc_equip.lua", "proc_fusion.lua", "proc_fusion_spell.lua", "proc_gemini.lua", "proc_link.lua",
        "proc_maximum.lua", "proc_normal.lua", "proc_pendulum.lua", "proc_persistent.lua", "proc_ritual.lua",
        "proc_rush.lua", "proc_skill.lua", "proc_spirit.lua", "proc_synchro.lua", "proc_union.lua",
        "proc_workaround.lua", "proc_xyz.lua"
    )

    /** Scarica uno script (chiamato anche dal motore C++ durante il duello). */
    fun fetchScript(name: String): Boolean {
        val safe = name.substringAfterLast('/')
        if (!Regex("^[A-Za-z0-9_\\-.]+\\.lua$").matches(safe)) return false
        val dest = File(scriptsDir, safe)
        if (dest.exists() && dest.length() > 0) return true
        val missing = File(scriptsDir, "$safe.missing")
        if (missing.exists()) return false
        val base = Net.scriptsBase(latestData)
        val paths = if (Regex("^c\\d+\\.lua$").matches(safe))
            listOf("official/$safe", "pre-errata/$safe", "unofficial/$safe") else listOf(safe)
        var allNotFound = true
        for (p in paths) {
            val code = Net.fetch("$base/$p", dest)
            if (code == 200) return true
            if (code != 404) allNotFound = false
        }
        // lo script non esiste (es. mostri Normali): lo segniamo per non riprovare a ogni duello
        if (allNotFound) {
            scriptsDir.mkdirs()
            try { missing.createNewFile() } catch (_: Exception) {}
        }
        return false
    }

    /** Prepara in anticipo gli script delle carte di entrambi i mazzi. */
    suspend fun prefetchScripts(codes: Collection<Int>, onProgress: (Int, Int) -> Unit) = coroutineScope {
        scriptsDir.mkdirs()
        val names = baseScripts + codes.toSet().filter { id ->
            val c = get(id)
            // i mostri Normali senza effetto non hanno script
            c == null || !(c.isMonster && (c.type and Types.NORMAL) != 0L && (c.type and Types.PENDULUM) == 0L)
        }.map { "c$it.lua" }
        val todo = names.filter { !File(scriptsDir, it).exists() && !File(scriptsDir, "$it.missing").exists() }
        var done = 0
        val sem = Semaphore(8)
        onProgress(0, todo.size)
        todo.map { n ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    fetchScript(n)
                    synchronized(this@CardRepository) { done++ }
                    onProgress(done, todo.size)
                }
            }
        }.awaitAll()
    }
}
