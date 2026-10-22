package com.leandro.f1clashcoach.core.engine

import com.leandro.f1clashcoach.core.model.DriverWeights
import com.leandro.f1clashcoach.core.model.Garage
import com.leandro.f1clashcoach.core.model.Progress
import com.leandro.f1clashcoach.core.model.StatWeights
import com.leandro.f1clashcoach.core.model.Track
import com.leandro.f1clashcoach.core.model.TrackCatalog

data class UpgradeAdvice(
    val itemId: String,
    val name: String,
    val kind: String,
    val level: Int,
    val canUpgradeNow: Boolean,
    val gain: Double,
    val gainIsEstimate: Boolean,
    val coins: Int,
    /** Pontos de desempenho por 1.000 moedas. */
    val efficiency: Double,
    val reason: String,
)

data class UpgradePlan(
    val ranked: List<UpgradeAdvice>,
    /** Upgrades que cabem no orçamento atual, na ordem recomendada. */
    val doNow: List<UpgradeAdvice>,
    val coinsLeft: Long,
    val collectCards: List<UpgradeAdvice>,
)

/**
 * Prioriza upgrades por ganho de desempenho por moeda, ponderado pelas pistas
 * em que você mais corre (ou todas, se não houver histórico).
 */
object UpgradePlanner {

    /** Ganho estimado por nível quando o próximo nível não é conhecido. */
    private const val EST_GAIN_PER_LEVEL = 0.07

    fun plan(garage: Garage, focusTracks: List<Track> = TrackCatalog.tracks): UpgradePlan {
        val tracks = focusTracks.ifEmpty { TrackCatalog.tracks }
        val carW = averageCar(tracks.map { it.carWeights })
        val drvW = averageDriver(tracks.map { it.driverWeights })

        // Peças equipadas e as melhores de cada slot valem mais: são as que você usa.
        val usefulPartIds = garage.parts.groupBy { it.slot }.values.flatMap { list ->
            list.filter { it.stats.total > 0 }.sortedByDescending { carW.score(it.stats) }.take(2).map { it.id }
        }.toSet() + garage.parts.filter { it.equipped || it.featured }.map { it.id }
        val usefulDriverIds = garage.drivers.filter { it.stats.total > 0 }.sortedByDescending { drvW.score(it.stats) }.take(3).map { it.id }.toSet() +
            garage.drivers.filter { it.equipped || it.featured }.map { it.id }

        val advices = mutableListOf<UpgradeAdvice>()
        for (p in garage.parts) {
            val next = p.nextLevelStats
            val gain = when {
                next != null -> carW.score(next) - carW.score(p.stats)
                p.stats.total > 0 -> carW.score(p.stats) * EST_GAIN_PER_LEVEL
                else -> heuristic(p.equipped, p.progress.level, p.featured)
            }
            val relevance = if (p.id in usefulPartIds) 1.0 else 0.35
            advices += advice(p.id, p.name, p.slot.label, p.progress.level, p.progress.canUpgrade, gain * relevance, next == null, p.progress.upgradeCoins, p.progress.cardsMissing, p.progress, p.equipped)
        }
        for (d in garage.drivers) {
            val next = d.nextLevelStats
            val gain = when {
                next != null -> drvW.score(next) - drvW.score(d.stats)
                d.stats.total > 0 -> drvW.score(d.stats) * EST_GAIN_PER_LEVEL
                else -> heuristic(d.equipped, d.progress.level, d.featured)
            }
            val relevance = if (d.id in usefulDriverIds) 1.0 else 0.35
            advices += advice(d.id, d.name, if (d.legendary) "Piloto lendário" else "Piloto", d.progress.level, d.progress.canUpgrade, gain * relevance, next == null, d.progress.upgradeCoins, d.progress.cardsMissing, d.progress, d.equipped)
        }

        val ranked = advices.sortedWith(compareByDescending<UpgradeAdvice> { it.canUpgradeNow }.thenByDescending { it.efficiency })
        var coins = garage.coins
        val doNow = mutableListOf<UpgradeAdvice>()
        for (a in ranked.filter { it.canUpgradeNow && it.gain > 0 }) {
            // custo desconhecido (0) não entra como "de graça": só o que dá para pagar de verdade
            if (a.coins > 0 && a.coins <= coins) {
                doNow += a
                coins -= a.coins
            }
        }
        val collect = advices.filter { !it.canUpgradeNow && it.gain > 0 && it.reason.startsWith("Faltam") }.sortedByDescending { it.gain }.take(5)
        return UpgradePlan(ranked, doNow, coins, collect)
    }

    /** Sem atributos lidos: o que está equipado (e em destaque) rende mais. */
    private fun heuristic(equipped: Boolean, level: Int, featured: Boolean): Double =
        (if (equipped) 12.0 else 3.0) * (1 + level * 0.05) * (if (featured) 1.3 else 1.0)

    private fun advice(
        id: String, name: String, kind: String, level: Int, can: Boolean, gain: Double,
        estimate: Boolean, coins: Int, cardsMissing: Int, progress: Progress, equipped: Boolean,
    ): UpgradeAdvice {
        val eff = if (coins > 0) gain / coins * 1000.0 else gain
        val tag = if (equipped) " • equipado" else ""
        val reason = when {
            !can && cardsMissing > 0 -> "Faltam $cardsMissing cartas (${progress.cardsOwned}/${progress.cardsNeeded})$tag"
            !can -> "Informe cartas/custo para avaliar"
            coins == 0 -> "Cartas prontas (${progress.cardsOwned}/${progress.cardsNeeded})$tag"
            else -> "+${"%.1f".format(gain)} pts por ${coins} moedas$tag"
        }
        return UpgradeAdvice(id, name, kind, level, can, gain, estimate, coins, eff, reason)
    }

    fun averageCar(ws: List<StatWeights>): StatWeights = StatWeights(
        ws.map { it.speed }.average(), ws.map { it.cornering }.average(), ws.map { it.powerUnit }.average(),
        ws.map { it.qualifying }.average(), ws.map { it.pitStop }.average(), ws.map { it.overtakeMode }.average(),
    )

    fun averageDriver(ws: List<DriverWeights>): DriverWeights = DriverWeights(
        ws.map { it.overtaking }.average(), ws.map { it.defending }.average(), ws.map { it.qualifying }.average(),
        ws.map { it.raceStart }.average(), ws.map { it.tyreManagement }.average(),
    )
}
