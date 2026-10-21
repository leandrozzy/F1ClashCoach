package com.leandro.f1clashcoach.core.engine

import com.leandro.f1clashcoach.core.model.Garage
import com.leandro.f1clashcoach.core.model.GrandPrixState
import com.leandro.f1clashcoach.core.model.PartSlot
import com.leandro.f1clashcoach.core.model.RaceRecord
import com.leandro.f1clashcoach.core.model.SeriesInfo
import com.leandro.f1clashcoach.core.model.TrackCatalog
import kotlin.math.exp
import kotlin.math.roundToInt

// ---------------------------------------------------------------- chance de vitória no duelo

data class DuelOdds(
    val winPct: Int,
    /** Bandeiras esperadas nesta corrida (ganho x perda). */
    val expectedFlags: Double?,
    val verdict: String,
    val tips: List<String>,
)

object DuelOddsCalc {
    /** Curva base: ~±100 de pontuação ≈ 78/22. Ajustada pelo seu histórico com rivais parecidos. */
    fun estimate(myScore: Int, rivalScore: Int, races: List<RaceRecord>, series: SeriesInfo?): DuelOdds {
        val diff = myScore - rivalScore
        val base = 1.0 / (1.0 + exp(-diff / 80.0))
        val similar = races.filter { it.won != null && it.myScore != null && it.rivalScore != null && kotlin.math.abs((it.myScore - it.rivalScore) - diff) <= 40 }
        val p = if (similar.size >= 5) {
            val hist = similar.count { it.won == true }.toDouble() / similar.size
            val w = (similar.size / 20.0).coerceAtMost(0.6)
            base * (1 - w) + hist * w
        } else base
        val win = series?.winFlags
        val loss = series?.lossFlags ?: win
        val exp = if (win != null && loss != null) p * win - (1 - p) * loss else null
        val pct = (p * 100).roundToInt().coerceIn(3, 97)
        val verdict = when {
            pct >= 70 -> "Favorito"
            pct >= 45 -> "Equilibrado"
            pct >= 25 -> "Azarão"
            else -> "Muito difícil"
        }
        val tips = when {
            pct >= 70 -> listOf("Plano padrão, sem arriscar: largue bem e controle o desgaste.", "Guarde o Overtake para defender se o rival acelerar.")
            pct >= 45 -> listOf("Classificação e pit decidem: use o pneu mais rápido na largada.", "Pare 1 volta antes do rival para ganhar posição (undercut).")
            else -> listOf(
                "Arrisque na estratégia: uma parada a menos ou pneu diferente do rival.",
                "Use o Overtake cedo e torça por chuva/safety car: só a estratégia vira esse duelo.",
                "Se perder vários seguidos assim, evolua antes de continuar (perde bandeiras).",
            )
        }
        return DuelOdds(pct, exp, verdict, tips)
    }
}

// ---------------------------------------------------------------- estratégia que mais venceu por pista

data class TrackPlay(val trackId: String, val trackName: String, val races: Int, val wins: Int, val bestStrategy: String?, val bestStrategyWins: Int, val bestStrategyRaces: Int)

object TrackPlaybook {
    fun build(races: List<RaceRecord>): List<TrackPlay> =
        races.filter { it.won != null && it.trackId != "desconhecida" }.groupBy { it.trackId }.map { (id, rs) ->
            val byStrat = rs.filter { it.strategy.isNotBlank() }.groupBy { it.strategy }
                .map { (s, l) -> Triple(s, l.count { it.won == true }, l.size) }
                .filter { it.third >= 2 }
                .maxWithOrNull(compareBy<Triple<String, Int, Int>>({ it.second.toDouble() / it.third }, { it.third }))
            TrackPlay(id, TrackCatalog.byId(id)?.name ?: id, rs.size, rs.count { it.won == true }, byStrat?.first, byStrat?.second ?: 0, byStrat?.third ?: 0)
        }.sortedByDescending { it.races }

    fun forTrack(races: List<RaceRecord>, trackId: String): TrackPlay? = build(races).firstOrNull { it.trackId == trackId }
}

// ---------------------------------------------------------------- Grand Prix semanal

data class GpPlan(val tier: String?, val lines: List<String>)

object GpPlanner {
    private val TIERS = listOf("Júnior", "Competidor", "Contendor", "Campeão")

    private fun rank(t: String?): Int = t?.let { name -> TIERS.indexOfFirst { Txt.compact(it) == Txt.compact(name) } } ?: -1

    fun plan(garage: Garage, gp: GrandPrixState?): GpPlan {
        val lines = ArrayList<String>()
        if (gp == null) return GpPlan(null, listOf("Abra o Grand Prix no jogo com a leitura ligada para eu ver a restrição desta semana."))
        val tier = gp.tier
        val tr = rank(tier).let { if (it < 0) TIERS.size - 1 else it }
        val noLegend = gp.legendaryAllowed == false
        fun okTier(min: String?) = rank(min) <= tr
        val drivers = garage.drivers.filter { okTier(it.minTier) && !(noLegend && it.legendary) }
            .sortedWith(compareByDescending<com.leandro.f1clashcoach.core.model.Driver> { it.featured }.thenByDescending { it.stats.total }.thenByDescending { it.progress.level })
        val parts = PartSlot.entries.mapNotNull { slot ->
            garage.parts.filter { it.slot == slot && okTier(it.minTier) }
                .maxWithOrNull(compareBy<com.leandro.f1clashcoach.core.model.Part>({ it.featured }, { it.stats.total }, { it.progress.level }))
        }
        lines += "Nível: ${tier ?: "?"}" + (SeriesCatalogCaps.cap(tier)?.let { " (peças/pilotos até a Série $it)" } ?: "")
        if (noLegend) lines += "⛔ Lendários proibidos nesta semana: tire ${garage.drivers.filter { it.legendary && it.equipped }.joinToString { it.name }.ifBlank { "os lendários" }} da escalação do GP."
        gp.featuredBoostPct?.let { lines += "⭐ Destaques valem +$it% aqui: priorize ${(garage.drivers.filter { it.featured }.map { it.name } + garage.parts.filter { it.featured }.map { it.name }).take(4).joinToString().ifBlank { "os recursos em destaque" }}." }
        if (drivers.isNotEmpty()) lines += "👤 Pilotos: " + drivers.take(2).joinToString(" + ") { it.name + if (it.featured) " ⭐" else "" }
        if (parts.isNotEmpty()) lines += "🔧 Peças: " + parts.joinToString(", ") { "${it.slot.label} ${it.name}" + if (it.featured) " ⭐" else "" }
        gp.recommendedScore?.let { rec ->
            val s = garage.teamScore
            lines += if (s > 0 && s >= rec) "Sua pontuação ($s) passa da recomendada ($rec): jogue para as primeiras posições."
            else if (s > 0) "Recomendado $rec, você tem $s: foque em terminar as corridas e pegar a tarefa do patrocinador."
            else "Pontuação recomendada: $rec."
        }
        if (gp.racesTotal != null && gp.racesPlayed != null) lines += "Corridas: ${gp.racesPlayed}/${gp.racesTotal}" + if (gp.racesPlayed < gp.racesTotal) " — jogue todas antes do fim da rodada." else " ✓"
        return GpPlan(tier, lines)
    }
}

private object SeriesCatalogCaps {
    fun cap(tier: String?): Int? = tier?.let { t -> com.leandro.f1clashcoach.core.model.SeriesCatalog.gpTierCaps.entries.firstOrNull { Txt.compact(it.key) == Txt.compact(t) }?.value }
}

// ---------------------------------------------------------------- loja: cartas que você procura

object StoreWatch {
    /** Itens que valem cartas: os que faltam cartas para evoluir e os escalados/em destaque. */
    fun wishlist(garage: Garage): List<String> {
        val plan = UpgradePlanner.plan(garage)
        val missing = plan.collectCards.map { it.name }
        val core = garage.drivers.filter { it.equipped }.map { it.name } + garage.parts.filter { it.equipped }.map { it.name }
        return (missing + core).distinct()
    }

    /** Ofertas da loja que batem com a lista de desejos. */
    fun matches(offers: List<String>, wishlist: List<String>): List<String> {
        fun k(s: String) = Txt.compact(s).replace('Z', 'A')
        return offers.filter { o ->
            val ko = k(o)
            ko.length >= 4 && wishlist.any { w -> val kw = k(w); kw.length >= 4 && (ko.contains(kw.takeLast(6)) || kw.contains(ko.takeLast(6))) }
        }.distinct()
    }
}
