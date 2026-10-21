package com.leandro.f1clashcoach.core.engine

import com.leandro.f1clashcoach.core.model.DrivingPace
import com.leandro.f1clashcoach.core.model.Track
import com.leandro.f1clashcoach.core.model.Tyre
import com.leandro.f1clashcoach.core.model.Weather
import kotlin.math.ceil

/** Estado de um piloto lido da tela. Campos nulos = não foram lidos. */
data class DriverLive(
    val position: Int? = null,
    val tyre: Tyre? = null,
    /** Vida restante do pneu, 0..100. */
    val tyreLife: Int? = null,
    val gapAhead: Double? = null,
    val gapBehind: Double? = null,
)

data class RaceSnapshot(
    val lap: Int? = null,
    val totalLaps: Int? = null,
    val weather: Weather = Weather.DRY,
    val rainExpected: Boolean = false,
    /** Carga da bateria/ERS 0..100. */
    val energy: Int? = null,
    val drivers: List<DriverLive> = emptyList(),
) {
    val lapsRemaining: Int? get() = if (lap != null && totalLaps != null) (totalLaps - lap + 1).coerceAtLeast(0) else null
}

enum class TipLevel { URGENT, IMPORTANT, INFO }

data class Tip(val level: TipLevel, val title: String, val detail: String = "")

/**
 * Motor determinístico de decisão em tempo real. Roda local, sem internet,
 * em milissegundos — por isso é ele (e não a IA) que fala durante a corrida.
 */
class LiveCoach(private val wear: WearModel = WearModel()) {

    fun advise(s: RaceSnapshot, track: Track?, tyreManagement: Int = 50): List<Tip> {
        val tips = mutableListOf<Tip>()
        val remaining = s.lapsRemaining

        if (s.lap == 1) {
            tips += Tip(TipLevel.INFO, "Largada: use energia na 1ª reta", "Race Start conta mais agora; ganhe posições antes de poupar pneu.")
        }

        s.drivers.forEachIndexed { i, d ->
            val who = "Piloto ${i + 1}"
            val tyre = d.tyre
            // 1) Clima x pneu
            if (tyre != null) {
                if (s.weather == Weather.HEAVY_RAIN && tyre != Tyre.WET && tyre != Tyre.INTER) {
                    tips += Tip(TipLevel.URGENT, "$who: BOX → Chuva (W)", "Chuva forte com pneu de seco perde muitos segundos por volta.")
                } else if (s.weather == Weather.LIGHT_RAIN && tyre.isDry) {
                    tips += Tip(TipLevel.URGENT, "$who: BOX → Intermediário", "Chuva leve: o intermediário é o mais rápido.")
                } else if (s.weather == Weather.DRY && !tyre.isDry && !s.rainExpected) {
                    tips += Tip(TipLevel.URGENT, "$who: BOX → pneu de seco", "Pista secou; pneu de chuva derrete no seco.")
                } else if (s.weather == Weather.DRY && s.rainExpected && tyre.isDry && (d.tyreLife ?: 100) < 45) {
                    tips += Tip(TipLevel.IMPORTANT, "$who: segure a parada", "Chuva chegando — pare uma vez só, direto para o Intermediário.")
                }
            }
            // 2) Pneu x voltas restantes
            val life = d.tyreLife
            if (tyre != null && life != null && remaining != null && track != null && remaining > 0) {
                val perLapNeutral = wear.wearPerLap(track, tyre, tyreManagement) * 100
                val perLapConserve = wear.wearPerLap(track, tyre, tyreManagement, DrivingPace.CONSERVE) * 100
                val perLapAttack = wear.wearPerLap(track, tyre, tyreManagement, DrivingPace.ATTACK) * 100
                val lapsNeutral = life / perLapNeutral
                when {
                    life <= 12 -> tips += Tip(TipLevel.URGENT, "$who: BOX AGORA", "Pneu com $life% — risco de perder muito tempo.")
                    life / perLapConserve < remaining - 0.3 && life < 35 ->
                        tips += Tip(TipLevel.URGENT, "$who: pare nesta volta", "Pneu não chega ao fim (${life}% para $remaining voltas).")
                    lapsNeutral < remaining ->
                        tips += Tip(TipLevel.IMPORTANT, "$who: CONSERVAR", "Conservando, o pneu aguenta ~${ceil(life / perLapConserve).toInt()} voltas; faltam $remaining.")
                    life / perLapAttack >= remaining ->
                        tips += Tip(TipLevel.INFO, "$who: pode ATACAR", "Pneu sobra mesmo atacando até o fim.")
                }
            }
            // 3) Disputa
            val ahead = d.gapAhead
            val behind = d.gapBehind
            if (ahead != null && ahead < 1.0) {
                tips += Tip(TipLevel.IMPORTANT, "$who: ataque (${"%.1f".format(ahead)}s)", "Use Overtake Mode/energia na próxima reta.")
            } else if (behind != null && behind < 0.8) {
                tips += Tip(TipLevel.IMPORTANT, "$who: defenda (${"%.1f".format(behind)}s)", "Guarde energia para a reta e não gaste pneu atacando.")
            }
        }

        // 4) Energia
        val energy = s.energy
        if (energy != null && remaining != null) {
            if (remaining <= 1 && energy > 20) {
                tips += Tip(TipLevel.IMPORTANT, "Última volta: gaste toda a energia", "Bateria com $energy% não vale nada depois da bandeirada.")
            } else if (energy < 15 && remaining > 2) {
                tips += Tip(TipLevel.INFO, "Recarregue a bateria", "Energia em $energy%; recarregue nas voltas sem disputa.")
            }
        }
        if (s.rainExpected && s.weather == Weather.DRY) {
            tips += Tip(TipLevel.INFO, "Chuva prevista", "Planeje a parada para quando a chuva começar.")
        }

        return tips.distinctBy { it.title }.sortedBy { it.level.ordinal }
    }
}
