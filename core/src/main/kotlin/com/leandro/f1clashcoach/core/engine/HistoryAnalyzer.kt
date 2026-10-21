package com.leandro.f1clashcoach.core.engine

import com.leandro.f1clashcoach.core.model.RaceRecord
import com.leandro.f1clashcoach.core.model.Track
import com.leandro.f1clashcoach.core.model.TrackCatalog
import com.leandro.f1clashcoach.core.model.Weather

data class TrackStat(val track: Track, val races: Int, val wins: Int) {
    val winRate: Double get() = if (races == 0) 0.0 else wins.toDouble() / races
}

data class HistoryReport(
    val races: Int,
    val wins: Int,
    val winRate: Double,
    val recentWinRate: Double,
    val avgGrid: Double?,
    val avgFinish: Double?,
    val byTrack: List<TrackStat>,
    val insights: List<String>,
) {
    val worstTracks: List<TrackStat> get() = byTrack.filter { it.races >= 2 }.sortedBy { it.winRate }.take(3)
    val bestTracks: List<TrackStat> get() = byTrack.filter { it.races >= 2 }.sortedByDescending { it.winRate }.take(3)
}

/** Lê o histórico e transforma em diagnósticos acionáveis. */
object HistoryAnalyzer {

    fun analyze(records: List<RaceRecord>): HistoryReport {
        val decided = records.filter { it.won != null }
        val wins = decided.count { it.won == true }
        val recent = decided.sortedByDescending { it.timestamp }.take(20)
        val grids = records.flatMap { it.gridPositions }
        val finishes = records.flatMap { it.finishPositions }
        val byTrack = decided.groupBy { it.trackId }.mapNotNull { (id, rs) ->
            TrackCatalog.byId(id)?.let { TrackStat(it, rs.size, rs.count { r -> r.won == true }) }
        }.sortedByDescending { it.races }

        val insights = mutableListOf<String>()
        if (decided.size < 5) {
            insights += "Registre pelo menos 5 corridas (o modo Coach faz isso sozinho na tela de resultado) para eu achar padrões."
        }
        val winRate = if (decided.isEmpty()) 0.0 else wins.toDouble() / decided.size
        val recentRate = if (recent.isEmpty()) 0.0 else recent.count { it.won == true }.toDouble() / recent.size
        if (decided.size >= 10) {
            when {
                recentRate > winRate + 0.1 -> insights += "Você está em boa fase: ${(recentRate * 100).toInt()}% nas últimas 20 contra ${(winRate * 100).toInt()}% no geral."
                recentRate < winRate - 0.1 -> insights += "Queda recente de rendimento: provavelmente subiu de série com carro abaixo do nível. Foque upgrades nas peças equipadas."
            }
            if (winRate >= 0.6) insights += "Taxa de vitória acima de 60%: você está pronto para disputar a promoção de série."
        }
        val avgGrid = grids.takeIf { it.isNotEmpty() }?.average()
        val avgFinish = finishes.takeIf { it.isNotEmpty() }?.average()
        if (avgGrid != null && avgFinish != null && grids.size >= 6) {
            val gained = avgGrid - avgFinish
            when {
                gained > 1.0 -> insights += "Você ganha em média ${"%.1f".format(gained)} posições na corrida: ritmo bom, classificação fraca. Priorize atributo Qualifying (peças e pilotos)."
                gained < -1.0 -> insights += "Você perde em média ${"%.1f".format(-gained)} posições na corrida: falta ritmo/pneu. Priorize Speed/Cornering e Tyre Management, e revise a estratégia de paradas."
            }
        }
        val rainRaces = decided.filter { it.weather != Weather.DRY }
        if (rainRaces.size >= 3) {
            val rr = rainRaces.count { it.won == true }.toDouble() / rainRaces.size
            if (rr < winRate - 0.15) insights += "Você vai pior na chuva (${(rr * 100).toInt()}%). Pare para o intermediário assim que a chuva aparecer — o Coach avisa no overlay."
        }
        byTrack.filter { it.races >= 3 && it.winRate < 0.35 }.forEach {
            insights += "${it.track.name} é seu ponto fraco (${it.wins}/${it.races}). Use o setup sugerido para pista ${it.track.type.label.lowercase()}."
        }
        return HistoryReport(records.size, wins, winRate, recentRate, avgGrid, avgFinish, byTrack, insights)
    }
}
