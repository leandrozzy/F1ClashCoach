package com.leandro.f1clashcoach.core.engine

import com.leandro.f1clashcoach.core.model.DrivingPace
import com.leandro.f1clashcoach.core.model.Track
import com.leandro.f1clashcoach.core.model.Tyre
import com.leandro.f1clashcoach.core.model.Weather
import kotlin.math.max

data class RaceContext(
    val track: Track,
    val laps: Int,
    /** Clima previsto por volta (índice 0 = volta 1). Vazio = seco a corrida toda. */
    val weatherByLap: List<Weather> = emptyList(),
    val tyreManagement: Int = 50,
    /** Tempo médio de pit stop das peças, em segundos. */
    val pitStopTime: Double = 1.0,
    val pitLaneLoss: Double = 16.0,
    val availableTyres: Set<Tyre> = Tyre.entries.toSet(),
    val startTyre: Tyre? = null,
) {
    fun weatherAt(lap: Int): Weather = weatherByLap.getOrNull(lap - 1) ?: weatherByLap.lastOrNull() ?: Weather.DRY
}

data class Stint(val tyre: Tyre, val fromLap: Int, val toLap: Int, val pace: DrivingPace) {
    val laps: Int get() = toLap - fromLap + 1
}

data class StrategyPlan(
    val stints: List<Stint>,
    val estimatedTime: Double,
    val endWear: Double,
    val notes: List<String>,
) {
    val stops: Int get() = stints.size - 1
    val pitLaps: List<Int> get() = stints.drop(1).map { it.fromLap - 1 }

    fun summary(): String = stints.joinToString(" → ") { "${it.tyre.short} (V${it.fromLap}-${it.toLap}, ${it.pace.label})" }
}

/**
 * Simula todas as estratégias razoáveis (0 a 2 paradas, compostos e ritmo por stint)
 * e devolve as melhores. Tempos são relativos (segundos), servem para comparar planos.
 */
class RaceStrategist(private val wear: WearModel = WearModel()) {

    fun bestPlans(ctx: RaceContext, top: Int = 3): List<StrategyPlan> {
        require(ctx.laps in 1..80) { "Número de voltas inválido" }
        val results = ArrayList<StrategyPlan>()
        val allDry = (1..ctx.laps).all { ctx.weatherAt(it) == Weather.DRY }
        val tyres = ctx.availableTyres.filter { !allDry || it.isDry }.ifEmpty { ctx.availableTyres.toList() }
        val paces = DrivingPace.entries

        fun evaluate(stints: List<Stint>) {
            if (ctx.startTyre != null && stints.first().tyre != ctx.startTyre) return
            val sim = simulate(ctx, stints) ?: return
            results += sim
        }

        val n = ctx.laps
        for (t1 in tyres) for (p1 in paces) {
            evaluate(listOf(Stint(t1, 1, n, p1)))
            for (a in 1 until n) for (t2 in tyres) for (p2 in paces) {
                evaluate(listOf(Stint(t1, 1, a, p1), Stint(t2, a + 1, n, p2)))
                if (n <= 25) for (b in a + 1 until n) for (t3 in tyres) {
                    // No 3º stint usa o mesmo ritmo do 2º para limitar a busca.
                    evaluate(listOf(Stint(t1, 1, a, p1), Stint(t2, a + 1, b, p2), Stint(t3, b + 1, n, p2)))
                }
            }
        }

        val sorted = results.sortedBy { it.estimatedTime }
        val distinct = ArrayList<StrategyPlan>()
        for (p in sorted) {
            val key = p.stints.map { it.tyre } to p.stops
            if (distinct.none { (it.stints.map { s -> s.tyre } to it.stops) == key }) distinct += p
            if (distinct.size >= top) break
        }
        return distinct
    }

    /** Simula volta a volta. Retorna null se a estratégia estoura o pneu. */
    fun simulate(ctx: RaceContext, stints: List<Stint>): StrategyPlan? {
        var time = 0.0
        var worstEnd = 0.0
        val notes = mutableListOf<String>()
        stints.forEachIndexed { i, s ->
            var used = 0.0
            for (lap in s.fromLap..s.toLap) {
                val w = ctx.weatherAt(lap)
                used += wear.wearPerLap(ctx.track, s.tyre, ctx.tyreManagement, s.pace) * weatherWearFactor(s.tyre, w)
                if (used > 1.0) return null
                time += lapTime(s.tyre, w, s.pace, used)
            }
            worstEnd = max(worstEnd, used)
            if (i > 0) time += ctx.pitLaneLoss + ctx.pitStopTime
        }
        if (stints.size > 1 && stints.zipWithNext().any { (a, b) -> a.tyre == b.tyre && a.pace == b.pace && ctx.weatherByLap.isEmpty() }) {
            notes += "Parada com o mesmo composto: só vale se o pneu não aguenta."
        }
        if (worstEnd > 0.85) notes += "Pneu chega no limite (${(worstEnd * 100).toInt()}% gasto) — atenção ao ritmo."
        return StrategyPlan(stints, time, worstEnd, notes)
    }

    companion object {
        fun lapTime(tyre: Tyre, weather: Weather, pace: DrivingPace, used: Double): Double {
            val compound = when (weather) {
                Weather.DRY -> when (tyre) {
                    Tyre.SOFT -> 0.0; Tyre.MEDIUM -> 0.5; Tyre.HARD -> 1.0; Tyre.INTER -> 4.0; Tyre.WET -> 7.0
                }
                Weather.LIGHT_RAIN -> when (tyre) {
                    Tyre.SOFT, Tyre.MEDIUM, Tyre.HARD -> 5.0; Tyre.INTER -> 0.0; Tyre.WET -> 1.5
                }
                Weather.HEAVY_RAIN -> when (tyre) {
                    Tyre.SOFT, Tyre.MEDIUM, Tyre.HARD -> 10.0; Tyre.INTER -> 2.0; Tyre.WET -> 0.0
                }
            }
            val paceDelta = when (pace) { DrivingPace.CONSERVE -> 0.5; DrivingPace.NEUTRAL -> 0.0; DrivingPace.ATTACK -> -0.6 }
            val degradation = if (used > 0.6) (used - 0.6) * 4.0 else 0.0
            return compound + paceDelta + degradation
        }

        /** Pneu de chuva no seco se desgasta muito mais rápido. */
        fun weatherWearFactor(tyre: Tyre, weather: Weather): Double = when {
            weather == Weather.DRY && !tyre.isDry -> 1.8
            weather != Weather.DRY && tyre.isDry -> 1.3
            else -> 1.0
        }
    }
}
