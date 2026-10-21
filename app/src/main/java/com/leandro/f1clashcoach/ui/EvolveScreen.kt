package com.leandro.f1clashcoach.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leandro.f1clashcoach.core.ai.CoachPrompts
import com.leandro.f1clashcoach.core.engine.HistoryAnalyzer
import com.leandro.f1clashcoach.core.engine.UpgradePlanner
import com.leandro.f1clashcoach.core.model.RaceRecord
import com.leandro.f1clashcoach.core.model.TrackCatalog
import com.leandro.f1clashcoach.core.model.Weather
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
fun EvolveScreen(vm: MainViewModel, modifier: Modifier, goAi: () -> Unit) {
    val data by vm.store.data.collectAsState()
    val report = remember(data.races) { HistoryAnalyzer.analyze(data.races) }
    // Foco: pistas onde você mais corre (ou todas).
    val focus = remember(report) { report.byTrack.take(6).map { it.track } }
    val plan = remember(data.garage, focus) { UpgradePlanner.plan(data.garage, focus) }
    var addRace by remember { mutableStateOf(false) }
    var removeRace by remember { mutableStateOf<RaceRecord?>(null) }
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")) }

    LazyColumn(modifier) {
        item {
            SectionCard("Plano de upgrades") {
                if (data.garage.parts.isEmpty() && data.garage.drivers.isEmpty()) {
                    Text("Cadastre a garagem para ver o plano.", color = Warn, fontSize = 13.sp)
                }
                if (plan.doNow.isNotEmpty()) {
                    Text("Faça agora (cabe nas suas ${formatK(data.garage.coins)} moedas):", fontWeight = FontWeight.Bold, color = Good)
                    plan.doNow.forEachIndexed { i, a -> Text("${i + 1}. ${a.name} (${a.kind}, nv ${a.level}) — ${a.reason}${if (a.gainIsEstimate) " ~" else ""}", fontSize = 13.sp) }
                    Text("Sobram ${formatK(plan.coinsLeft)} moedas.", fontSize = 12.sp, color = Muted)
                }
                if (plan.collectCards.isNotEmpty()) {
                    Text("Junte cartas destes (maior ganho):", fontWeight = FontWeight.Bold)
                    plan.collectCards.forEach { a -> Text("• ${a.name} (${a.kind}) — ${a.reason}", fontSize = 13.sp) }
                }
                Text("~ = ganho estimado. Informe os atributos do próximo nível na peça para precisão total.", fontSize = 11.sp, color = Muted)
                OutlinedButton(onClick = { vm.ask(CoachPrompts.EVOLUTION_PLAN, webSearch = true); goAi() }, modifier = Modifier.fillMaxWidth()) {
                    Text("📈 Plano de 7 dias com IA")
                }
            }
        }
        item {
            SectionCard("Desempenho") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    Stat("Corridas", report.races.toString())
                    Stat("Vitórias", "${(report.winRate * 100).toInt()}%")
                    Stat("Últimas 20", "${(report.recentWinRate * 100).toInt()}%")
                    Stat("Grid→Final", report.avgGrid?.let { g -> report.avgFinish?.let { f -> "%.1f→%.1f".format(g, f) } } ?: "—")
                }
                if (report.bestTracks.isNotEmpty()) Text("Melhores: " + report.bestTracks.joinToString { "${it.track.name} ${(it.winRate * 100).toInt()}%" }, fontSize = 13.sp, color = Good)
                if (report.worstTracks.isNotEmpty()) Text("Piores: " + report.worstTracks.joinToString { "${it.track.name} ${(it.winRate * 100).toInt()}%" }, fontSize = 13.sp, color = F1Red)
                report.insights.forEach { Text("💡 $it", fontSize = 13.sp) }
            }
        }
        item {
            SectionCard("Histórico", action = { TextButton(onClick = { addRace = true }) { Text("+ Corrida") } }) {
                if (data.races.isEmpty()) Text("Com o Coach ligado as corridas entram sozinhas pela tela de resultado.", fontSize = 13.sp, color = Muted)
            }
        }
        items(data.races.sortedByDescending { it.timestamp }.take(100), key = { it.id }) { r ->
            Column(Modifier.fillMaxWidth().clickable { removeRace = r }.padding(horizontal = 18.dp, vertical = 6.dp)) {
                Text("${if (r.won == true) "🏆" else if (r.won == false) "❌" else "•"} ${TrackCatalog.byId(r.trackId)?.name ?: r.trackId}  ${r.weather.label}  ${r.strategy}", fontSize = 14.sp)
                Text("${fmt.format(Date(r.timestamp))} • grid ${r.gridPositions.joinToString("/").ifEmpty { "?" }} → ${r.finishPositions.joinToString("/").ifEmpty { "?" }} • ${r.source}", fontSize = 11.sp, color = Muted)
            }
        }
        item { Text(" ", Modifier.padding(20.dp)) }
    }

    if (addRace) AddRaceDialog(onDismiss = { addRace = false }) { vm.store.addRace(it); addRace = false }
    removeRace?.let { r ->
        AlertDialog(
            onDismissRequest = { removeRace = null },
            title = { Text("Excluir corrida?") },
            text = { Text("${TrackCatalog.byId(r.trackId)?.name ?: r.trackId} — ${fmt.format(Date(r.timestamp))}") },
            confirmButton = { TextButton(onClick = { vm.store.removeRace(r.id); removeRace = null }) { Text("Excluir", color = F1Red) } },
            dismissButton = { TextButton(onClick = { removeRace = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun AddRaceDialog(onDismiss: () -> Unit, onSave: (RaceRecord) -> Unit) {
    var track by remember { mutableStateOf(TrackCatalog.tracks.first()) }
    var won by remember { mutableStateOf(true) }
    var weather by remember { mutableStateOf(Weather.DRY) }
    var grid by remember { mutableStateOf("") }
    var finish by remember { mutableStateOf("") }
    var strategy by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Registrar corrida") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Picker("Pista", TrackCatalog.tracks, track, { it.name }, { track = it }, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(won, { won = true }, { Text("Vitória") }); FilterChip(!won, { won = false }, { Text("Derrota") })
                }
                Picker("Clima", Weather.entries, weather, { it.label }, { weather = it }, Modifier.fillMaxWidth())
                OutlinedTextField(grid, { grid = it }, label = { Text("Grid (ex: 4/7)") }, singleLine = true)
                OutlinedTextField(finish, { finish = it }, label = { Text("Chegada (ex: 2/5)") }, singleLine = true)
                OutlinedTextField(strategy, { strategy = it }, label = { Text("Pneus (ex: M→H)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                fun nums(s: String) = s.split('/', ',', ' ').mapNotNull { it.trim().toIntOrNull() }
                onSave(RaceRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), track.id, gridPositions = nums(grid),
                    finishPositions = nums(finish), won = won, weather = weather, strategy = strategy))
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
