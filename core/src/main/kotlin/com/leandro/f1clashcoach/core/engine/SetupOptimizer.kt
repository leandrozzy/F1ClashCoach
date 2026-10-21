package com.leandro.f1clashcoach.core.engine

import com.leandro.f1clashcoach.core.model.Driver
import com.leandro.f1clashcoach.core.model.DriverWeights
import com.leandro.f1clashcoach.core.model.Garage
import com.leandro.f1clashcoach.core.model.Part
import com.leandro.f1clashcoach.core.model.PartSlot
import com.leandro.f1clashcoach.core.model.StatWeights
import com.leandro.f1clashcoach.core.model.Track
import kotlin.math.roundToInt

data class SlotChoice(
    val slot: PartSlot,
    val best: Part?,
    val bestScore: Double,
    val equipped: Part?,
    val runnerUp: Part?,
)

data class SetupResult(
    val track: Track,
    val choices: List<SlotChoice>,
    val drivers: List<Driver>,
    val carScore: Double,
    val driverScore: Double,
    /** Ganho estimado em relação ao que está equipado hoje. */
    val gainVsEquipped: Double,
    val changes: List<String>,
)

/**
 * Escolhe a melhor peça por slot e a melhor dupla de pilotos para uma pista.
 * Os atributos são aditivos, então a melhor combinação é o melhor item de cada slot
 * segundo os pesos da pista.
 */
object SetupOptimizer {

    fun optimize(
        garage: Garage,
        track: Track,
        carWeights: StatWeights = track.carWeights,
        driverWeights: DriverWeights = track.driverWeights,
    ): SetupResult {
        val changes = mutableListOf<String>()
        var gain = 0.0

        val choices = PartSlot.entries.map { slot ->
            val ranked = garage.parts.filter { it.slot == slot }
                .sortedByDescending { carWeights.score(it.stats) }
            val best = ranked.firstOrNull()
            val equipped = garage.parts.firstOrNull { it.slot == slot && it.equipped }
            val bestScore = best?.let { carWeights.score(it.stats) } ?: 0.0
            if (best != null && equipped != null && best.id != equipped.id) {
                val delta = bestScore - carWeights.score(equipped.stats)
                if (delta > 0.0) {
                    gain += delta
                    changes += "${slot.label}: troque ${equipped.name} → ${best.name} (+${delta.roundToInt()} pts)"
                }
            } else if (best != null && equipped == null) {
                changes += "${slot.label}: equipe ${best.name}"
            }
            SlotChoice(slot, best, bestScore, equipped, ranked.getOrNull(1))
        }

        val rankedDrivers = garage.drivers.sortedByDescending { driverWeights.score(it.stats) }
        val bestDrivers = rankedDrivers.take(2)
        val equippedDrivers = garage.drivers.filter { it.equipped }
        val newDrivers = bestDrivers.filter { d -> equippedDrivers.none { it.id == d.id } }
        val outDrivers = equippedDrivers.filter { d -> bestDrivers.none { it.id == d.id } }
        newDrivers.zip(outDrivers).forEach { (inD, outD) ->
            val delta = driverWeights.score(inD.stats) - driverWeights.score(outD.stats)
            gain += delta
            changes += "Piloto: troque ${outD.name} → ${inD.name} (+${delta.roundToInt()} pts)"
        }
        if (equippedDrivers.isEmpty()) newDrivers.forEach { changes += "Piloto: escale ${it.name}" }

        return SetupResult(
            track = track,
            choices = choices,
            drivers = bestDrivers,
            carScore = choices.sumOf { it.bestScore },
            driverScore = bestDrivers.sumOf { driverWeights.score(it.stats) },
            gainVsEquipped = gain,
            changes = changes,
        )
    }
}
