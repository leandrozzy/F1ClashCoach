package com.leandro.f1clashcoach.core.engine

import com.leandro.f1clashcoach.core.model.DrivingPace
import com.leandro.f1clashcoach.core.model.Track
import com.leandro.f1clashcoach.core.model.Tyre
import com.leandro.f1clashcoach.core.model.TyreObservation

/**
 * Modelo de desgaste de pneus. Começa com valores padrão e se ajusta
 * automaticamente com as observações reais (OCR ou registro manual).
 * Desgaste é expresso como fração da vida do pneu consumida por volta.
 */
class WearModel(observations: List<TyreObservation> = emptyList()) {

    private val learned: Map<Pair<String, Tyre>, Pair<Double, Int>> =
        observations
            .filter { it.lapsOnTyre >= 1.0 && it.wearPercent in 1.0..100.0 }
            .groupBy { it.trackId to it.tyre }
            .mapValues { (_, obs) ->
                val perLap = obs.map { (it.wearPercent / 100.0) / it.lapsOnTyre / paceFactor(it.pace) }
                perLap.average() to perLap.size
            }

    fun samples(trackId: String, tyre: Tyre): Int = learned[trackId to tyre]?.second ?: 0

    /** Desgaste por volta em ritmo neutro. [tyreManagement] é a média do atributo dos pilotos (0..99). */
    fun wearPerLap(track: Track, tyre: Tyre, tyreManagement: Int, pace: DrivingPace = DrivingPace.NEUTRAL): Double {
        val prior = BASE.getValue(tyre) * (0.7 + 0.6 * track.tyreStress)
        val base = learned[track.id to tyre]?.let { (avg, n) ->
            // Média bayesiana: o padrão vale como 3 observações.
            (prior * PRIOR_WEIGHT + avg * n) / (PRIOR_WEIGHT + n)
        } ?: prior
        val tm = (1.0 - tyreManagement.coerceIn(0, 99) / 400.0)
        return base * tm * paceFactor(pace)
    }

    companion object {
        private const val PRIOR_WEIGHT = 3.0
        val BASE: Map<Tyre, Double> = mapOf(
            Tyre.SOFT to 0.30, Tyre.MEDIUM to 0.20, Tyre.HARD to 0.14, Tyre.INTER to 0.18, Tyre.WET to 0.15,
        )

        fun paceFactor(p: DrivingPace): Double = when (p) {
            DrivingPace.CONSERVE -> 0.75
            DrivingPace.NEUTRAL -> 1.0
            DrivingPace.ATTACK -> 1.35
        }
    }
}
