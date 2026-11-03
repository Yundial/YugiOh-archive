package com.yundial.ygotester.data

import android.content.Context

object Types {
    const val MONSTER = 0x1L
    const val SPELL = 0x2L
    const val TRAP = 0x4L
    const val NORMAL = 0x10L
    const val EFFECT = 0x20L
    const val FUSION = 0x40L
    const val RITUAL = 0x80L
    const val SPIRIT = 0x200L
    const val UNION = 0x400L
    const val GEMINI = 0x800L
    const val TUNER = 0x1000L
    const val SYNCHRO = 0x2000L
    const val TOKEN = 0x4000L
    const val QUICKPLAY = 0x10000L
    const val CONTINUOUS = 0x20000L
    const val EQUIP = 0x40000L
    const val FIELD = 0x80000L
    const val COUNTER = 0x100000L
    const val FLIP = 0x200000L
    const val TOON = 0x400000L
    const val XYZ = 0x800000L
    const val PENDULUM = 0x1000000L
    const val LINK = 0x4000000L
    const val EXTRA = FUSION or SYNCHRO or XYZ or LINK
}

object Loc {
    const val DECK = 0x01
    const val HAND = 0x02
    const val MZONE = 0x04
    const val SZONE = 0x08
    const val GRAVE = 0x10
    const val REMOVED = 0x20
    const val EXTRA = 0x40
    const val OVERLAY = 0x80
}

object Pos {
    const val FACEUP_ATTACK = 0x1
    const val FACEDOWN_ATTACK = 0x2
    const val FACEUP_DEFENSE = 0x4
    const val FACEDOWN_DEFENSE = 0x8
    fun faceUp(p: Int) = p and 0x5 != 0
    fun defense(p: Int) = p and 0xc != 0
}

/** Testi italiani: etichette e stringhe di sistema di EDOPro (strings.conf). */
object Texts {
    private val system = HashMap<Int, String>()

    fun init(ctx: Context) {
        for (f in listOf("strings_en.conf", "strings_it.conf")) {
            try {
                ctx.assets.open(f).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (l in lines) {
                        if (!l.startsWith("!system ")) continue
                        val rest = l.removePrefix("!system ")
                        val sp = rest.indexOf(' ')
                        if (sp <= 0) continue
                        val id = rest.substring(0, sp).toIntOrNull() ?: continue
                        system[id] = rest.substring(sp + 1)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun system(id: Int): String? = system[id]

    /** Descrizione di un effetto/opzione: stringa di sistema oppure stringa della carta (codice<<20 | indice) */
    fun desc(d: Long, fallbackCode: Int = 0): String {
        if (d == 0L) return if (fallbackCode != 0) "Effetto di «${CardRepository.name(fallbackCode)}»" else ""
        if (d < 10000L) return (system(d.toInt()) ?: "Opzione $d").replace("%ls", "").replace("%d", "")
        val code = (d shr 20).toInt()
        val idx = (d and 0xfffff).toInt()
        val s = CardRepository.effectString(code, idx)
        val n = CardRepository.name(code)
        return if (s != null) "«$n»: $s" else "Effetto ${idx + 1} di «$n»"
    }

    fun phase(p: Int): String = when (p) {
        0x01 -> "Draw Phase"
        0x02 -> "Standby Phase"
        0x04 -> "Main Phase 1"
        0x08, 0x10, 0x20, 0x40, 0x80 -> "Battle Phase"
        0x100 -> "Main Phase 2"
        0x200 -> "End Phase"
        else -> ""
    }

    fun location(l: Int): String = when (l and 0x7f) {
        Loc.DECK -> "Deck"
        Loc.HAND -> "mano"
        Loc.MZONE -> "Zona Mostri"
        Loc.SZONE -> "Zona Magie/Trappole"
        Loc.GRAVE -> "Cimitero"
        Loc.REMOVED -> "bandite"
        Loc.EXTRA -> "Extra Deck"
        else -> ""
    }

    fun zone(pl: Int, l: Int, s: Int): String {
        val who = if (pl == 0) "Tua" else "Avversario"
        val z = if (l == Loc.MZONE) when (s) {
            5 -> "Zona Mostri Extra sinistra"
            6 -> "Zona Mostri Extra destra"
            else -> "Zona Mostri ${s + 1}"
        } else when (s) {
            5 -> "Zona Terreno"
            6, 7 -> "Zona Pendulum"
            else -> "Zona Magie/Trappole ${s + 1}"
        }
        return "$who – $z"
    }

    fun position(p: Int): String = when (p) {
        Pos.FACEUP_ATTACK -> "Attacco scoperto"
        Pos.FACEDOWN_ATTACK -> "Attacco coperto"
        Pos.FACEUP_DEFENSE -> "Difesa scoperta"
        Pos.FACEDOWN_DEFENSE -> "Difesa coperta"
        else -> "Posizione"
    }

    private val attributes = listOf(0x1 to "TERRA", 0x2 to "ACQUA", 0x4 to "FUOCO", 0x8 to "VENTO",
        0x10 to "LUCE", 0x20 to "OSCURITÀ", 0x40 to "DIVINO")

    private val races = listOf(
        0x1L to "Guerriero", 0x2L to "Incantatore", 0x4L to "Fata", 0x8L to "Demone", 0x10L to "Zombie",
        0x20L to "Macchina", 0x40L to "Acqua", 0x80L to "Pyro", 0x100L to "Roccia", 0x200L to "Bestia Alata",
        0x400L to "Pianta", 0x800L to "Insetto", 0x1000L to "Tuono", 0x2000L to "Drago", 0x4000L to "Bestia",
        0x8000L to "Guerriero-Bestia", 0x10000L to "Dinosauro", 0x20000L to "Pesce", 0x40000L to "Serpente Marino",
        0x80000L to "Rettile", 0x100000L to "Psichico", 0x200000L to "Bestia Divina", 0x400000L to "Dio Creatore",
        0x800000L to "Wyrm", 0x1000000L to "Cyberso", 0x2000000L to "Illusione"
    )

    fun attribute(a: Int): String = attributes.filter { a and it.first != 0 }.joinToString("/") { it.second }
    fun race(r: Long): String = races.filter { r and it.first != 0L }.joinToString("/") { it.second }
    fun allAttributes() = attributes
    fun allRaces() = races

    /** Riga descrittiva tipo "Mostro Effetto/Synchro · Drago · LUCE · Liv. 8" */
    fun typeLine(c: CardInfo): String {
        val t = c.type
        if (c.isSpell || c.isTrap) {
            val kind = if (c.isSpell) "Magia" else "Trappola"
            val sub = when {
                t and Types.QUICKPLAY != 0L -> "Rapida"
                t and Types.CONTINUOUS != 0L -> "Continua"
                t and Types.EQUIP != 0L -> "Equipaggiamento"
                t and Types.FIELD != 0L -> "Terreno"
                t and Types.COUNTER != 0L -> "Contro"
                t and Types.RITUAL != 0L -> "Rituale"
                else -> "Normale"
            }
            return "$kind $sub"
        }
        val parts = ArrayList<String>()
        if (t and Types.FUSION != 0L) parts.add("Fusione")
        if (t and Types.SYNCHRO != 0L) parts.add("Synchro")
        if (t and Types.XYZ != 0L) parts.add("Xyz")
        if (t and Types.LINK != 0L) parts.add("Link")
        if (t and Types.RITUAL != 0L) parts.add("Rituale")
        if (t and Types.PENDULUM != 0L) parts.add("Pendulum")
        if (t and Types.TUNER != 0L) parts.add("Tuner")
        if (t and Types.FLIP != 0L) parts.add("Scoperta")
        if (t and Types.TOON != 0L) parts.add("Toon")
        if (t and Types.SPIRIT != 0L) parts.add("Spirit")
        if (t and Types.UNION != 0L) parts.add("Union")
        if (t and Types.GEMINI != 0L) parts.add("Gemello")
        if (t and Types.EFFECT != 0L) parts.add("Effetto") else if (t and Types.NORMAL != 0L) parts.add("Normale")
        val lv = when {
            c.isLink -> "LINK-${c.linkRating}"
            t and Types.XYZ != 0L -> "Rango ${c.level}"
            else -> "Liv. ${c.level}"
        }
        return "Mostro ${parts.joinToString("/")} · ${race(c.race)} · ${attribute(c.attribute)} · $lv"
    }

    fun statLine(c: CardInfo): String = when {
        !c.isMonster -> ""
        c.isLink -> "ATK ${stat(c.atk)}"
        else -> "ATK ${stat(c.atk)} / DEF ${stat(c.def)}"
    }

    fun stat(v: Int) = if (v < 0) "?" else v.toString()
}
