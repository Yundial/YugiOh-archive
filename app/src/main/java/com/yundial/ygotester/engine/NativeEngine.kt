package com.yundial.ygotester.engine

import com.yundial.ygotester.data.CardRepository

/** Funzioni del motore C++ (ocgcore di EDOPro + bot), vedi app/src/main/cpp/jni/jni_bridge.cpp */
object NativeEngine {
    init {
        System.loadLibrary("ygotester")
    }

    external fun loadCards(path: String): Boolean
    external fun create(
        scriptDir: String, humanMain: IntArray, humanExtra: IntArray, botMain: IntArray, botExtra: IntArray,
        difficulty: Int, humanFirst: Boolean, seed: Long,
    ): Long
    external fun lastError(): String
    external fun advance(handle: Long): String
    external fun respond(handle: Long, json: String): String
    external fun surrender(handle: Long): String
    external fun destroy(handle: Long)
}

/** Chiamato dal motore quando manca lo script di una carta. */
object ScriptFetcher {
    @JvmStatic
    fun fetch(name: String): Boolean = CardRepository.fetchScript(name)
}
