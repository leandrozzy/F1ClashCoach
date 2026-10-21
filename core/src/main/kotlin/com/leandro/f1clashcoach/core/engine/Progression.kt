package com.leandro.f1clashcoach.core.engine

import com.leandro.f1clashcoach.core.model.Garage
import com.leandro.f1clashcoach.core.model.RaceRecord
import com.leandro.f1clashcoach.core.model.SeriesInfo
import kotlin.math.ceil
import kotlin.math.max

data class SeriesStep(
    val series: SeriesInfo,
    /** Bandeiras que faltam para desbloquear esta série. */
    val flagsMissing: Int?,
    val scoreGap: Int?,
    val estRaces: Int?,
)

data class ProgressionPlan(
    val currentSeries: Int,
    val flags: Int?,
    val teamScore: Int,
    val target: SeriesInfo?,
    val steps: List<SeriesStep>,
    val winRate: Double,
    val netFlagsPerRace: Double?,
    val starsPerWeekNow: Int?,
    val starsPerWeekTarget: Int?,
    val advice: List<String>,
)

/**
 * "Rumo à Série 12 (Grand Slam de Ouro)": quantas bandeiras, quanta pontuação de equipe
 * e quantas corridas faltam, e quantas estrelas isso rende para o clube.
 */
object Progression {

    fun plan(garage: Garage, series: List<SeriesInfo>, races: List<RaceRecord>, flags: Int?, targetSeries: Int = 12, racesPerWeek: Int = 70): ProgressionPlan {
        val current = garage.series
        val decided = races.filter { it.won != null }.sortedByDescending { it.timestamp }.take(50)
        val wr = if (decided.size >= 5) decided.count { it.won == true }.toDouble() / decided.size else 0.5
        val cur = series.firstOrNull { it.number == current }
        val net = cur?.let { s -> s.winFlags?.let { w -> wr * w - (1 - wr) * (s.lossFlags ?: w) } }

        val steps = series.filter { it.number in (current + 1)..targetSeries }.map { s ->
            val gap = s.scoreMin?.let { (it - garage.teamScore).coerceAtLeast(0) }
            val est = s.flagsToUnlock?.let { need -> if (net != null && net > 0) ceil(need / net).toInt() else null }
            SeriesStep(s, s.flagsToUnlock, gap, est)
        }
        val target = series.firstOrNull { it.number == targetSeries }
        val advice = mutableListOf<String>()
        val gapTarget = target?.scoreMin?.let { it - garage.teamScore }
        if (gapTarget != null && gapTarget > 0) {
            advice += "Para competir de igual na Série $targetSeries (${target.name}) a pontuação da equipe precisa chegar a ~${target.scoreMin} (hoje ${garage.teamScore}): faltam $gapTarget pontos. Evolua primeiro o que está equipado."
        }
        val next = steps.firstOrNull()
        if (next != null && cur?.scoreMax != null && garage.teamScore > cur.scoreMax) {
            advice += "Sua pontuação (${garage.teamScore}) já passou da faixa da Série $current (até ${cur.scoreMax}): você enfrenta rivais mais fracos aqui — ótimo momento para farmar bandeiras."
        } else if (cur?.scoreMin != null && garage.teamScore < cur.scoreMin) {
            advice += "Sua pontuação está abaixo da faixa da Série $current (${cur.scoreMin}–${cur.scoreMax}). Evoluir antes de correr aumenta muito a taxa de vitória."
        }
        if (net != null) {
            if (net <= 0) advice += "Com ${(wr * 100).toInt()}% de vitórias você perde bandeiras na Série $current. Pare, evolua e use a estratégia do Coach antes de voltar."
            else next?.estRaces?.let { advice += "No ritmo atual (${(wr * 100).toInt()}% de vitórias, +${"%.1f".format(net)} bandeiras/corrida) faltam ~$it corridas para a Série ${next.series.number}." }
        }
        advice += "Use o 'Flag Storm' (bandeiras em dobro) em sessões longas de Duelos e o 'Reputation Rise' (reputação em dobro) quando houver Exibição de clube ativa."
        val starsNow = cur?.prizeStars?.let { (it * racesPerWeek * wr).toInt() }
        val starsTarget = target?.prizeStars?.let { (it * racesPerWeek * max(wr, 0.5)).toInt() }
        if (cur?.prizeStars != null && target?.prizeStars != null) {
            advice += "Cada vitória na Série $current dá ★${cur.prizeStars} ao clube; na Série $targetSeries dá ★${target.prizeStars} — subir de série multiplica a reputação do FLA F1."
        }
        return ProgressionPlan(current, flags, garage.teamScore, target, steps, wr, net, starsNow, starsTarget, advice)
    }
}
