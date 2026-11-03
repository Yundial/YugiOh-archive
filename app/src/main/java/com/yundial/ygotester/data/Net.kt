package com.yundial.ygotester.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Indirizzi online e scaricamenti. */
object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Versioni fissate e collaudate insieme al motore incluso nell'app
    const val SCRIPTS_COMMIT = "2bd02f71383b87d7dee174909a96d10491930c45"
    const val CDB_COMMIT = "781ec580fb82ced7cd17bae290ee2985824a0def"

    fun scriptsBase(latest: Boolean): String =
        "https://raw.githubusercontent.com/ProjectIgnis/CardScripts/" + (if (latest) "master" else SCRIPTS_COMMIT)

    fun cdbUrl(latest: Boolean): String =
        "https://raw.githubusercontent.com/ProjectIgnis/BabelCDB/" + (if (latest) "master" else CDB_COMMIT) + "/cards.cdb"

    const val ITALIAN_CARDS = "https://db.ygoprodeck.com/api/v7/cardinfo.php?language=it"

    fun setCodeInfo(code: String): String =
        "https://db.ygoprodeck.com/api/v7/cardsetsinfo.php?setcode=" + URLEncoder.encode(code, "UTF-8")

    fun imageSmall(id: Int) = "https://images.ygoprodeck.com/images/cards_small/$id.jpg"
    fun imageBig(id: Int) = "https://images.ygoprodeck.com/images/cards/$id.jpg"

    /** Scarica [url] in [dest] (passando da un file temporaneo). Ritorna false se fallisce. */
    fun download(url: String, dest: File, progress: ((Long, Long) -> Unit)? = null): Boolean =
        fetch(url, dest, progress) == 200

    /** Come [download] ma restituisce il codice HTTP (-1 se errore di rete). */
    fun fetch(url: String, dest: File, progress: ((Long, Long) -> Unit)? = null): Int {
        val tmp = File(dest.parentFile, dest.name + ".part")
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return resp.code
                val body = resp.body ?: return -1
                val total = body.contentLength()
                dest.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            progress?.invoke(done, total)
                        }
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (tmp.renameTo(dest)) 200 else -1
        } catch (e: Exception) {
            tmp.delete()
            -1
        }
    }

    fun getString(url: String): String? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }
}
