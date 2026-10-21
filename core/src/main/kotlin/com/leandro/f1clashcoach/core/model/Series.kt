package com.leandro.f1clashcoach.core.model

import kotlinx.serialization.Serializable

/** Dados de uma Série de Duelos (lidos do cartão da série no jogo). */
@Serializable
data class SeriesInfo(
    val number: Int,
    val name: String,
    val trackName: String = "",
    val laps: Int? = null,
    val scoreMin: Int? = null,
    val scoreMax: Int? = null,
    val winFlags: Int? = null,
    val lossFlags: Int? = null,
    val prizeCoins: Long? = null,
    /** Estrelas de reputação para o clube por vitória. */
    val prizeStars: Int? = null,
    val entryFee: Long? = null,
    /** Bandeiras que ainda faltam para desbloquear (0 = desbloqueada). */
    val flagsToUnlock: Int? = null,
    val statFocus: List<String> = emptyList(),
    val seenAt: Long = 0,
)

/**
 * Catálogo inicial (telas do jogo em outubro/2026). As pistas giram a cada ~2 semanas;
 * o app substitui estes valores pelo que lê na tela de seleção de série.
 */
object SeriesCatalog {
    val defaults: List<SeriesInfo> = listOf(
        SeriesInfo(7, "Plataforma de Lançamento", "Melbourne", 9, 750, 1000, prizeCoins = 2_300_000, prizeStars = 50, entryFee = 1_150_000, statFocus = listOf("Início de Corrida", "Curvas")),
        SeriesInfo(8, "Inovação", "Madri", 5, 850, 1100, 22, 22, 5_000_000, 65, 2_500_000, statFocus = listOf("Ultrapassando", "Curvas")),
        SeriesInfo(9, "Velocidade Terminal", "Silverstone", 7, 950, 1200, 24, null, 10_800_000, 80, flagsToUnlock = 1_325, statFocus = listOf("Gerenciamento de pneus", "Velocidade")),
        SeriesInfo(10, "Grand Slam de Bronze", "Xangai", 7, 1050, 1300, 26, null, 22_800_000, 100, flagsToUnlock = 4_125, statFocus = listOf("Defesa", "Unidade de Potência")),
        SeriesInfo(11, "Grand Slam de Prata", "Baku", 7, 1250, 1550, 28, 28, 46_000_000, 120, flagsToUnlock = 8_745, statFocus = listOf("Gerenciamento de pneus", "Unidade de Potência")),
        SeriesInfo(12, "Grand Slam de Ouro", "Miami", 6, 1450, 1799, 30, 30, 90_000_000, 150, flagsToUnlock = 16_025, statFocus = listOf("Ultrapassando", "Velocidade")),
    )

    fun merged(seen: Map<Int, SeriesInfo>): List<SeriesInfo> {
        val base = defaults.associateBy { it.number }.toMutableMap()
        seen.forEach { (n, s) ->
            val d = base[n]
            base[n] = if (d == null) s else s.copy(
                name = s.name.ifBlank { d.name }, trackName = s.trackName.ifBlank { d.trackName }, laps = s.laps ?: d.laps,
                scoreMin = s.scoreMin ?: d.scoreMin, scoreMax = s.scoreMax ?: d.scoreMax, winFlags = s.winFlags ?: d.winFlags,
                lossFlags = s.lossFlags ?: d.lossFlags, prizeCoins = s.prizeCoins ?: d.prizeCoins, prizeStars = s.prizeStars ?: d.prizeStars,
                entryFee = s.entryFee ?: d.entryFee, statFocus = s.statFocus.ifEmpty { d.statFocus },
            )
        }
        return base.values.sortedBy { it.number }
    }

    /** Níveis do Grand Prix e o limite de série das peças/pilotos (central de ajuda da Hutch). */
    val gpTierCaps: Map<String, Int?> = linkedMapOf("Júnior" to 3, "Competidor" to 6, "Contendor" to 9, "Campeão" to null)
}
