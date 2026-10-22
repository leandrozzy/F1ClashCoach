package com.leandro.f1clashcoach.core.model

import com.leandro.f1clashcoach.core.engine.Txt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Peça do catálogo oficial da temporada, com atributos em cada nível. */
data class PartDef(val name: String, val slot: PartSlot, val rarity: Rarity, val series: Int, val levels: List<CarStats>) {
    val maxLevel: Int get() = levels.size
    fun at(level: Int): CarStats? = levels.getOrNull(level - 1)
}

/** Piloto do catálogo: atributos, cartas e moedas de cada nível. */
data class DriverDef(
    val name: String, val rarity: Rarity, val series: Int, val levels: List<DriverStats>,
    /** Cartas e moedas para chegar ao nível i+1 (índice 0 = nível 1, sempre 0). */
    val cards: List<Int>, val coins: List<Long>, val legacy: List<Int>,
) {
    val maxLevel: Int get() = levels.size
    val legendary: Boolean get() = rarity == Rarity.LEGENDARY
    fun at(level: Int): DriverStats? = levels.getOrNull(level - 1)
}

/** Item do catálogo cruzado com a sua garagem. */
data class CollectionItem(
    val name: String,
    val isDriver: Boolean,
    val rarity: Rarity,
    val slot: PartSlot?,
    val series: Int,
    val owned: Boolean,
    val level: Int?,
    val maxLevel: Int,
    val total: Int,
    val nextTotal: Int?,
    val maxTotal: Int,
    val cardsOwned: Int,
    val cardsNeeded: Int,
    val nextCoins: Long?,
    val coinsToMax: Long?,
    val featured: Boolean,
    val equipped: Boolean,
    val gameName: String?,
    val edition: String = "",
)

/**
 * Catálogo completo da temporada 2026: todas as peças e pilotos (inclusive lendários) com os atributos
 * de cada nível e o custo de evolução. Fonte: projeto F1Clash_Setup (licença MIT), dados da comunidade.
 */
object Catalog {
    @Serializable private data class RawPart(val n: String, val s: String, val r: String, val ser: Int, val l: List<List<Double>>)
    @Serializable private data class RawDriver(val n: String, val r: String, val ser: Int, val l: List<List<Long>>)
    @Serializable private data class Raw(val season: String, val source: String, val cards: List<Int>, val partCoins: List<List<Long>>, val parts: List<RawPart>, val drivers: List<RawDriver>)

    private val raw: Raw by lazy {
        val text = Catalog::class.java.getResourceAsStream("/catalog2026.json")!!.bufferedReader(Charsets.UTF_8).readText()
        Json { ignoreUnknownKeys = true }.decodeFromString(Raw.serializer(), text)
    }

    val season: String get() = raw.season
    val source: String get() = raw.source
    /** Cartas para passar do nível i+1 para i+2 (índice 0 = 1→2). Igual para todas as raridades. */
    val cardSteps: List<Int> get() = raw.cards

    private fun rarity(s: String) = runCatching { Rarity.valueOf(s) }.getOrDefault(Rarity.UNKNOWN)

    val parts: List<PartDef> by lazy {
        raw.parts.map { p ->
            PartDef(p.n, PartSlot.valueOf(p.s), rarity(p.r), p.ser, p.l.map { v ->
                CarStats(v[0].toInt(), v[1].toInt(), v[2].toInt(), v[3].toInt(), v[4], v.getOrElse(5) { 0.0 }.toInt())
            })
        }
    }

    val drivers: List<DriverDef> by lazy {
        raw.drivers.map { d ->
            DriverDef(d.n, rarity(d.r), d.ser,
                d.l.map { v -> DriverStats(v[0].toInt(), v[1].toInt(), v[2].toInt(), v[3].toInt(), v[4].toInt()) },
                d.l.map { it[5].toInt() }, d.l.map { it[6] }, d.l.map { it.getOrElse(7) { 0L }.toInt() })
        }
    }

    // ---------------------------------------------------------------- nomes

    /** Chave de nome: sem acento/pontos; "z" e "a" iguais (o OCR troca na fonte do jogo). */
    private fun key(s: String) = Txt.compact(s).replace("0", "O").replace('Z', 'A').replace('I', 'L')

    /** "F. Colapinto" (jogo) casa com "Colapinto" (catálogo); "D. Hill" com "D.Hill". */
    fun sameName(game: String, cat: String): Boolean {
        val g = key(game); val c = key(cat)
        if (g.isEmpty() || c.isEmpty()) return false
        if (g == c) return true
        val short = if (g.length < c.length) g else c
        val long = if (g.length < c.length) c else g
        if (short.length >= 4 && long.endsWith(short) && long.length - short.length <= 3) return true
        // 1 letra errada (2 em nomes longos), comparando o fim do nome do jogo com o do catálogo
        // compara só o sobrenome (depois do último ponto); se a base tem inicial (D.Hill/G.Hill), a inicial tem que bater
        val gs = key(game.substringAfterLast('.')); val cs = key(cat.substringAfterLast('.'))
        val initialOk = !cat.contains('.') || (game.contains('.') && key(game).firstOrNull() == key(cat).firstOrNull())
        if (initialOk && gs.length >= 5 && cs.length >= 5 && Txt.lev(gs, cs, 2) <= (if (cs.length >= 8) 2 else 1)) return true
        if (short.length >= 6 && long.length - short.length <= 3) {
            val tail = long.takeLast(short.length)
            // mesmo tamanho = os dois têm (ou não têm) inicial: a inicial tem que bater (D. Hill ≠ G. Hill)
            if (long.length == short.length && long[0] != short[0]) return false
            if (Txt.lev(tail, short, 2) <= (if (short.length >= 8) 2 else 1)) return true
        }
        return false
    }

    fun findPart(name: String): PartDef? = parts.firstOrNull { sameName(name, it.name) }
        ?: parts.firstOrNull { key(it.name).length >= 5 && com.leandro.f1clashcoach.core.engine.Txt.lev(key(it.name), key(name), 2) <= 1 }

    fun findDrivers(name: String): List<DriverDef> = drivers.filter { sameName(name, it.name) }

    fun findDriver(name: String, rarity: Rarity): DriverDef? {
        val all = findDrivers(name)
        return all.firstOrNull { it.rarity == rarity } ?: all.singleOrNull()
    }

    // ---------------------------------------------------------------- níveis e custos

    /** Nível a partir das cartas pedidas para o próximo (ex.: x/1000 → nível 8). */
    fun levelFromCardsNeeded(need: Int): Int? = cardSteps.indexOf(need).takeIf { it >= 0 }?.plus(1)

    fun partCoinsToNext(series: Int, level: Int): Long? = raw.partCoins.getOrNull(series - 1)?.getOrNull(level - 1)

    fun partCoinsToMax(def: PartDef, level: Int): Long? =
        (level until def.maxLevel).sumOf { partCoinsToNext(def.series, it) ?: return null }

    fun driverCoinsToNext(def: DriverDef, level: Int): Long? = def.coins.getOrNull(level)

    fun driverCoinsToMax(def: DriverDef, level: Int): Long = (level until def.maxLevel).sumOf { def.coins.getOrNull(it) ?: 0L }

    /** Raridade e nível exatos pelos atributos lidos (tabela dos pilotos escalados). */
    fun matchDriverStats(name: String, stats: DriverStats): Pair<DriverDef, Int>? {
        if (stats.total == 0) return null
        val got = listOf(stats.overtaking, stats.defending, stats.qualifying, stats.raceStart, stats.tyreManagement)
        var best: Pair<DriverDef, Int>? = null; var bestErr = Int.MAX_VALUE
        for (d in findDrivers(name)) d.levels.forEachIndexed { i, s ->
            val want = listOf(s.overtaking, s.defending, s.qualifying, s.raceStart, s.tyreManagement)
            // valores lidos como 0 (linha não lida) não contam; tolera 1 número lido errado
            val err = got.indices.count { got[it] != 0 && got[it] != want[it] }
            val read = got.count { it != 0 }
            if (read >= 3 && err <= 1 && err < bestErr) { best = d to i + 1; bestErr = err }
        }
        return best
    }

    // ---------------------------------------------------------------- nomes oficiais e limpeza

    /** Chave única do piloto: nome do catálogo (resolve "L. LawVSon", "L.LaSon"…) ou o nome lido limpo. */
    fun driverKey(name: String): String = findDrivers(name).firstOrNull()?.name?.let { "#$it" } ?: key(name)

    /** Nome para mostrar: inicial + sobrenome do catálogo ("L. Lawson"); fora do catálogo, o lido. */
    fun displayName(read: String): String {
        val d = findDrivers(read).firstOrNull()?.name ?: return read.trim()
        if (d.contains('.')) return d.replace(".", ". ")
        val initial = read.trim().firstOrNull()?.takeIf { it.isLetter() && read.contains('.') }?.uppercaseChar()
        return if (initial != null && read.indexOf('.') <= 2) "$initial. $d" else d
    }

    /**
     * Junta cartas repetidas (variações de OCR já salvas): mesmo piloto + mesma raridade (+ mesma edição) vira um só,
     * ficando com o maior progresso. Cartas de raridade desconhecida somem se o mesmo piloto já tem raridade conhecida.
     */
    fun dedupe(g: Garage): Garage {
        val drivers = g.drivers.map { d ->
            val defs = findDrivers(d.name)
            val r = if (defs.size == 1 && d.rarity != Rarity.SPECIAL) defs[0].rarity else d.rarity
            d.copy(name = displayName(d.name), rarity = r)
        }
        val knownKeys = drivers.filter { it.rarity != Rarity.UNKNOWN }.map { driverKey(it.name) }.toSet()
        val kept = drivers.filter { it.rarity != Rarity.UNKNOWN || driverKey(it.name) !in knownKeys }
            .groupBy { Triple(driverKey(it.name), it.rarity, it.edition) }
            .map { (_, same) ->
                val best = same.maxWith(compareBy<Driver>({ it.progress.level }, { it.progress.cardsNeeded }, { it.progress.cardsOwned }, { it.stats.total }))
                best.copy(equipped = same.any { it.equipped }, featured = same.any { it.featured })
            }
        val parts = g.parts.map { p -> findPart(p.name)?.let { def -> p.copy(name = def.name, slot = def.slot) } ?: p }
            .groupBy { it.name.lowercase() to it.slot }
            .map { (_, same) -> same.maxWith(compareBy<Part>({ it.progress.level }, { it.progress.cardsNeeded }, { it.progress.cardsOwned })).copy(equipped = same.any { it.equipped }) }
        return g.copy(drivers = kept, parts = parts)
    }

    // ---------------------------------------------------------------- preencher a garagem

    /** Completa a garagem com o catálogo: nível pelas cartas, atributos atuais e do próximo nível, custo. */
    fun enrich(g: Garage): Garage {
        val parts = g.parts.map { p ->
            val def = findPart(p.name) ?: return@map p
            val lvl = levelFromCardsNeeded(p.progress.cardsNeeded)?.takeIf { p.progress.level <= 1 || it == p.progress.level } ?: p.progress.level
            val cur = def.at(lvl)
            p.copy(
                rarity = def.rarity,
                stats = if (p.stats.total == 0 && cur != null) cur else p.stats,
                nextLevelStats = def.at(lvl + 1) ?: p.nextLevelStats,
                progress = p.progress.copy(level = lvl, upgradeCoins = if (p.progress.upgradeCoins == 0) (partCoinsToNext(def.series, lvl)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0) else p.progress.upgradeCoins),
            )
        }
        val drivers = g.drivers.map { d ->
            if (d.rarity == Rarity.SPECIAL) return@map d
            val exact = matchDriverStats(d.name, d.stats)
            val def = exact?.first ?: findDriver(d.name, d.rarity) ?: return@map d
            val lvl = exact?.second ?: levelFromCardsNeeded(d.progress.cardsNeeded)?.takeIf { d.progress.level <= 1 || it == d.progress.level } ?: d.progress.level
            val cur = def.at(lvl)
            d.copy(
                rarity = def.rarity,
                stats = if (d.stats.total == 0 && cur != null) cur else d.stats,
                nextLevelStats = def.at(lvl + 1) ?: d.nextLevelStats,
                progress = d.progress.copy(level = lvl, upgradeCoins = if (d.progress.upgradeCoins == 0) (driverCoinsToNext(def, lvl)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0) else d.progress.upgradeCoins),
            )
        }
        return g.copy(parts = parts, drivers = drivers)
    }

    /** Todos os pilotos do catálogo, com o que você tem (nível, cartas) e o que falta. */
    fun driverCollection(g: Garage): List<CollectionItem> = drivers.map { def ->
        val mine = g.drivers.filter { sameName(it.name, def.name) }
            .firstOrNull { it.rarity == def.rarity } ?: g.drivers.filter { sameName(it.name, def.name) && it.rarity == Rarity.UNKNOWN }
            .takeIf { findDrivers(def.name).size == 1 }?.firstOrNull()
        val lvl = mine?.progress?.level
        CollectionItem(
            def.name, true, def.rarity, null, def.series, mine != null, lvl, def.maxLevel,
            (lvl?.let { def.at(it) } ?: def.at(1))?.total ?: 0, lvl?.let { def.at(it + 1)?.total }, def.levels.last().total,
            mine?.progress?.cardsOwned ?: 0, mine?.progress?.cardsNeeded ?: 0,
            lvl?.let { driverCoinsToNext(def, it) } ?: def.coins.getOrNull(1), lvl?.let { driverCoinsToMax(def, it) },
            mine?.featured == true, mine?.equipped == true, mine?.name,
        )
    } + g.drivers.filter { d ->
        // especiais, fora da base (ex.: Herbert) ou raridade ainda não confirmada: mostra o que foi lido
        d.rarity == Rarity.SPECIAL || findDrivers(d.name).isEmpty() || (d.rarity == Rarity.UNKNOWN && findDrivers(d.name).size > 1)
    }.map { d ->
        // Edições especiais: fora do catálogo; mostra o que foi lido do jogo.
        CollectionItem(
            d.name, true, d.rarity, null, 0, true, d.progress.level, maxOf(d.progress.level, 7),
            d.stats.total, d.nextLevelStats?.total, d.stats.total,
            d.progress.cardsOwned, d.progress.cardsNeeded, d.progress.upgradeCoins.toLong().takeIf { it > 0 }, null,
            d.featured, d.equipped, d.name, edition = d.edition,
        )
    }

    fun partCollection(g: Garage): List<CollectionItem> = parts.map { def ->
        val mine = g.parts.firstOrNull { sameName(it.name, def.name) }
        val lvl = mine?.progress?.level
        CollectionItem(
            def.name, false, def.rarity, def.slot, def.series, mine != null, lvl, def.maxLevel,
            (lvl?.let { def.at(it) } ?: def.at(1))?.total ?: 0, lvl?.let { def.at(it + 1)?.total }, def.levels.last().total,
            mine?.progress?.cardsOwned ?: 0, mine?.progress?.cardsNeeded ?: 0,
            partCoinsToNext(def.series, lvl ?: 1), lvl?.let { partCoinsToMax(def, it) },
            mine?.featured == true, mine?.equipped == true, mine?.name,
        )
    }
}

/** Raridade pela cor do fundo da carta: azul = comum, laranja = rara, roxo = épica. */
object RarityColors {
    /** Pontos a amostrar (relativos ao canto superior esquerdo do nome, em fração da tela). */
    val offsets: List<Pair<Float, Float>> = listOf(0.005f, 0.015f, 0.17f, 0.18f).flatMap { dx -> listOf(0.035f, 0.05f, 0.065f, 0.08f).map { dy -> dx to -dy } }

    fun classify(pixels: List<Triple<Int, Int, Int>>): Rarity {
        var c = 0; var r = 0; var e = 0
        for ((red, green, blue) in pixels) {
            val max = maxOf(red, green, blue); val min = minOf(red, green, blue)
            if (max < 90 || max == 0 || (max - min).toFloat() / max < 0.35f) continue
            val d = (max - min).toFloat()
            var h = when (max) {
                red -> 60f * (((green - blue) / d) % 6f)
                green -> 60f * (((blue - red) / d) + 2f)
                else -> 60f * (((red - green) / d) + 4f)
            }
            if (h < 0) h += 360f
            when {
                h in 190f..235f -> c++
                h in 10f..45f -> r++
                h in 250f..300f -> e++
            }
        }
        val best = maxOf(c, r, e)
        if (best < 5) return Rarity.UNKNOWN
        return when (best) { c -> Rarity.COMMON; r -> Rarity.RARE; else -> Rarity.EPIC }
    }
}
