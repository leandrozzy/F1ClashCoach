package com.leandro.f1clashcoach.core.engine

import com.leandro.f1clashcoach.core.model.Track

/** Atributo que um reforço aumenta (ícones da tela de Reforços do jogo). */
enum class BoostStat(val label: String, val short: String) {
    OVERTAKING("Ultrapassagem", "ULT"), DEFENDING("Defesa", "DEF"), RACE_START("Largada", "LAR"), TYRE("Pneus", "PNEU"),
    SPEED("Velocidade", "VEL"), CORNERING("Curvas", "CUR"), POWER_UNIT("Potência", "POT"), PIT("Pit stop", "PIT"),
    OVERTAKE_MODE("Overtake", "OVT"),
}

data class BoosterDef(val name: String, val effects: Map<BoostStat, Int>)

/** Reforço que você tem (nome + quantidade lida na tela). */
data class BoosterOwned(val def: BoosterDef?, val name: String, val count: Int)

data class BoosterSlot(val driver: Int, val stint: Int, val booster: String, val why: String)

data class BoosterPlan(
    val slots: List<BoosterSlot>,
    val used: Map<String, Int>,
    val left: Map<String, Int>,
    val lines: List<String>,
    val missing: Int,
)

/**
 * Reforços da temporada 2026, lidos das telas do jogo (nome, ícone e valor de cada bônus).
 * Regras oficiais (central de ajuda da Hutch): cada piloto usa um reforço por trecho — um na largada
 * e mais um a cada pit stop —, os dois pilotos podem usar o mesmo, e reforço não aumenta Classificação.
 * "Largada" só vale na 1ª volta, então depois do pit não serve para nada.
 */
object Boosters {
    private fun b(name: String, vararg e: Pair<BoostStat, Int>) = BoosterDef(name, mapOf(*e))
    private val O = BoostStat.OVERTAKING; private val D = BoostStat.DEFENDING; private val L = BoostStat.RACE_START; private val T = BoostStat.TYRE
    private val S = BoostStat.SPEED; private val C = BoostStat.CORNERING; private val P = BoostStat.POWER_UNIT; private val PIT = BoostStat.PIT
    private val OM = BoostStat.OVERTAKE_MODE

    val all: List<BoosterDef> = listOf(
        b("Príncipe", D to 10, L to 20, C to 20),
        b("Djinn", D to 15, C to 15, P to 20),
        b("Oud", O to 15, C to 10, PIT to 25),
        b("Plataforma", L to 25, P to 15, PIT to 10),
        b("Meia-noite", L to 15, P to 25, PIT to 10),
        b("Xícara de chá", O to 10, L to 20, C to 20),
        b("Chama Eterna", O to 25, T to 10, S to 15),
        b("Cogumelo", P to 25, OM to 25),
        b("Águia", T to 20, S to 15, PIT to 15),
        b("Touro", O to 5, S to 20, P to 25),
        b("Lenhador", D to 15, T to 10, PIT to 25),
        b("Transmissão Máxima", O to 5, S to 25, P to 20),
        b("Arando", O to 20, D to 20, C to 10),
        b("Natal", O to 10, S to 20, C to 20),
        b("Coroa", D to 5, T to 20, PIT to 25),
    )

    fun find(name: String): BoosterDef? {
        val k = Txt.compact(name).replace('Z', 'A')
        if (k.length < 3) return null
        return all.firstOrNull { val d = Txt.compact(it.name); d == k || (k.length >= 4 && (d.startsWith(k) || k.startsWith(d))) || (d.length >= 5 && Txt.lev(d, k, 2) <= 1) }
    }

    fun owned(counts: Map<String, Int>): List<BoosterOwned> =
        counts.map { (n, c) -> find(n).let { d -> BoosterOwned(d, d?.name ?: n, c) } }.groupBy { it.name }
            .map { (n, l) -> BoosterOwned(l.first().def, n, l.maxOf { it.count }) }

    /** Peso de cada atributo nesta pista (pesos da pista) e nesta situação. */
    private fun weights(track: Track?, stint: Int, underdog: Boolean, favourite: Boolean, longStints: Boolean): Map<BoostStat, Double> {
        val car = track?.carWeights; val drv = track?.driverWeights
        val w = mutableMapOf(
            O to (drv?.overtaking ?: 1.0), D to (drv?.defending ?: 1.0), L to (if (stint == 0) (drv?.raceStart ?: 1.0) * 0.8 else 0.0),
            T to (drv?.tyreManagement ?: 1.0) * (if (longStints) 1.4 else 1.0),
            S to (car?.speed ?: 1.0), C to (car?.cornering ?: 1.0), P to (car?.powerUnit ?: 1.0),
            PIT to (car?.pitStop ?: 0.6) * (if (stint == 0) 0.6 else 0.8), OM to (car?.overtakeMode ?: 1.0),
        )
        if (underdog) { w[O] = w.getValue(O) * 1.4; w[D] = w.getValue(D) * 0.7; w[OM] = w.getValue(OM) * 1.3 }
        if (favourite) { w[D] = w.getValue(D) * 1.3; w[O] = w.getValue(O) * 0.85 }
        return w
    }

    fun score(def: BoosterDef, w: Map<BoostStat, Double>): Double = def.effects.entries.sumOf { (s, v) -> v * (w[s] ?: 1.0) }

    /**
     * Distribui os reforços que você TEM pelos 2 pilotos e pelos trechos da corrida ([stops] paradas).
     * Guarda os raros (1 unidade) para corridas difíceis, a não ser que você seja o azarão ou seja Grand Prix.
     */
    fun plan(counts: Map<String, Int>, track: Track?, stops: Int, winPct: Int?, important: Boolean = false): BoosterPlan {
        val owned = owned(counts).filter { it.def != null && it.count > 0 }
        val left = owned.associate { it.name to it.count }.toMutableMap()
        val underdog = winPct != null && winPct < 45
        val favourite = winPct != null && winPct >= 70
        val spendRare = underdog || important
        val stints = (stops + 1).coerceIn(1, 4)
        val slots = ArrayList<BoosterSlot>()
        var missing = 0
        for (stint in 0 until stints) {
            val w = weights(track, stint, underdog, favourite, stints <= 2)
            for (driver in 0..1) {
                val pick = owned.filter { (left[it.name] ?: 0) > 0 && (spendRare || it.count >= 2 || favourite.not()) }
                    .filter { spendRare || !(it.count == 1 && favourite) }
                    .maxByOrNull { score(it.def!!, w) - (if (!spendRare && (left[it.name] ?: 0) <= 1) 8.0 else 0.0) }
                if (pick == null) { missing++; continue }
                left[pick.name] = (left[pick.name] ?: 0) - 1
                val top = pick.def!!.effects.entries.sortedByDescending { (s, v) -> v * (w[s] ?: 1.0) }.take(2).joinToString(" ") { "${it.key.short}+${it.value}" }
                slots += BoosterSlot(driver, stint, pick.name, top)
            }
        }
        val used = slots.groupingBy { it.booster }.eachCount()
        val lines = ArrayList<String>()
        for (stint in 0 until stints) {
            val s = slots.filter { it.stint == stint }
            if (s.isEmpty()) continue
            val label = if (stint == 0) "Largada" else "Após o ${stint}º pit"
            lines += "$label: " + s.joinToString("  •  ") { "P${it.driver + 1} ${it.booster} (${it.why})" }
        }
        if (missing > 0) lines += "Faltam $missing reforços para cobrir a corrida toda — use no 1º trecho, que pesa mais."
        val spare = left.filter { it.value > 0 }.entries.sortedByDescending { it.value }.take(4)
        if (spare.isNotEmpty()) lines += "Ficam: " + spare.joinToString { "${it.key} ${it.value}" }
        return BoosterPlan(slots, used, left, lines, missing)
    }
}
