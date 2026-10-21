package com.leandro.f1clashcoach.core.engine

import com.leandro.f1clashcoach.core.model.Tyre
import com.leandro.f1clashcoach.core.model.Weather

enum class OvertakeState { READY, ACTIVE }

/** Painel de um piloto na parte de baixo da tela de corrida. */
data class DriverHud(
    val left: Boolean,
    val code: String? = null,
    val position: Int? = null,
    /** Carga da bateria/ERS 0..100. */
    val ers: Int? = null,
    /** Vida restante do pneu 0..100. */
    val tyreLife: Int? = null,
    val tyre: Tyre? = null,
    val overtake: OvertakeState? = null,
    val speedKmh: Int? = null,
    val pitRequested: Boolean = false,
    val inPit: Boolean = false,
    val stops: Int? = null,
    val finishPoints: Int? = null,
)

/** Tudo que é lido da tela de corrida (Duelos e Grand Prix usam a mesma tela). */
data class RaceHud(
    val lap: Int? = null,
    val totalLaps: Int? = null,
    val weatherWord: String? = null,
    val weather: Weather = Weather.DRY,
    val rainChance: Int? = null,
    val tempC: Int? = null,
    val timeLeft: String? = null,
    val messages: List<String> = emptyList(),
    val drivers: List<DriverHud> = emptyList(),
    /** Rival trocou para este pneu (mensagem do rádio). */
    val rivalPitTyre: Tyre? = null,
    val tyreWarning: Boolean = false,
    val lastLap: Boolean = false,
    val finished: Boolean = false,
) {
    fun toSnapshot(): RaceSnapshot = RaceSnapshot(
        lap = lap, totalLaps = totalLaps, weather = weather,
        rainExpected = (rainChance ?: 0) >= 40,
        energy = drivers.mapNotNull { it.ers }.minOrNull(),
        drivers = drivers.map { DriverLive(position = it.position, tyre = it.tyre, tyreLife = it.tyreLife) },
    )
}

/** Resultado do duelo (tela "VENCEDOR!" / "RESULTADOS"). */
data class DuelResult(
    val won: Boolean?,
    val myPoints: Int?,
    val rivalPoints: Int?,
    val flagsAfter: Int?,
    val fastestLap: Boolean,
)

/** Cores dos compostos no ícone de pneu (padrão F1). */
object TyreColors {
    /** Decide o composto pela maioria dos pixels do ícone (o branco do Hard aparece como reflexo nos outros). */
    fun vote(pixels: List<Triple<Int, Int, Int>>): Tyre? {
        val votes = pixels.mapNotNull { (r, g, b) -> classify(r, g, b) }.groupingBy { it }.eachCount()
        if (votes.isEmpty()) return null
        val colored = votes.filterKeys { it != Tyre.HARD }.maxByOrNull { it.value }
        val hard = votes[Tyre.HARD] ?: 0
        return if (colored != null && colored.value * 3 >= hard) colored.key else if (hard >= 3) Tyre.HARD else colored?.key
    }

    fun classify(r: Int, g: Int, b: Int): Tyre? {
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        if (max < 90) return null
        val sat = if (max == 0) 0.0 else (max - min).toDouble() / max
        return when {
            sat < 0.18 && max > 170 -> Tyre.HARD
            sat < 0.35 -> null
            r > 170 && g > 150 && b < 110 -> Tyre.MEDIUM
            r > 160 && g < 110 && b < 110 -> Tyre.SOFT
            b > 160 && r < 120 -> Tyre.WET
            g > 150 && r < 140 && b < 140 -> Tyre.INTER
            else -> null
        }
    }
}
