package com.leandro.f1clashcoach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.leandro.f1clashcoach.core.model.Catalog
import com.leandro.f1clashcoach.core.model.CollectionItem
import com.leandro.f1clashcoach.core.model.PartSlot
import com.leandro.f1clashcoach.core.model.Rarity

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
import androidx.compose.ui.Alignment
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
        // ---------- Fila de upgrades (gráfico de rendimento) ----------
        item {
            val ranked = plan.ranked.filter { it.gain > 0 && it.coins > 0 }.take(8)
            val maxEff = ranked.maxOfOrNull { it.efficiency }?.takeIf { it > 0 } ?: 1.0
            SectionCard("💰 Onde gastar as moedas") {
                Text("Barra = quanto o upgrade rende por moeda. Verde = dá para fazer agora.", fontSize = 12.sp, color = Muted)
                if (ranked.isEmpty()) Text("Abra Equipe › Pilotos e Carros no jogo com a leitura ligada: eu leio níveis e cartas e calculo com a base de todos os níveis.", fontSize = 13.sp, color = Warn)
                ranked.forEach { a ->
                    val now = a in plan.doNow
                    val part = data.garage.parts.firstOrNull { it.id == a.itemId }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        val drv = data.garage.drivers.firstOrNull { it.id == a.itemId }
                        ArtThumb(
                            if (part != null) com.leandro.f1clashcoach.art.ArtStore.partKey(part.name)
                            else com.leandro.f1clashcoach.art.ArtStore.driverKey(drv?.name ?: a.name, drv?.rarity ?: Rarity.UNKNOWN, drv?.edition ?: ""),
                            part?.rarity ?: drv?.rarity ?: Rarity.UNKNOWN, part?.slot, 44.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(a.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
                                if (now) Pill("agora", Good) else if (!a.canUpgradeNow) Pill("cartas", Muted)
                            }
                            MiniBar((a.efficiency / maxEff).toFloat(), if (now) Good else Sky, Modifier.fillMaxWidth().padding(vertical = 3.dp))
                            Text("nv ${a.level}→${a.level + 1} • +${"%.0f".format(a.gain)} atributos • ${formatK(a.coins.toLong())} moedas", fontSize = 11.sp, color = Muted)
                        }
                    }
                }
                if (plan.doNow.isNotEmpty()) Text("Fazendo os verdes sobram ${formatK(plan.coinsLeft)} moedas.", fontSize = 12.sp, color = Good)
                OutlinedButton(onClick = { vm.ask(CoachPrompts.EVOLUTION_PLAN, webSearch = true); goAi() }, modifier = Modifier.fillMaxWidth()) { Text("📈 Plano de 7 dias com IA") }
            }
        }

        // ---------- Cartas para buscar (rumo à S12): lista curta, não grade cinza ----------
        item {
            val allDrivers = remember(data.garage) { Catalog.driverCollection(data.garage) }
            val allParts = remember(data.garage) { Catalog.partCollection(data.garage) }
            val wantDrivers = allDrivers.filter { reachable(it) }.sortedByDescending { it.maxTotal }.distinctBy { it.name }.take(5)
            val wantParts = PartSlot.entries.mapNotNull { sl -> allParts.filter { it.slot == sl && reachable(it) }.maxByOrNull { it.maxTotal } }
            SectionCard("🎯 Melhores cartas até a Série 12") {
                Text("✓ = você já tem. As outras: caixotes, loja e eventos.", fontSize = 12.sp, color = Muted)
                (wantDrivers + wantParts).forEach { c ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(rarityColor(c.rarity)))
                        Spacer(Modifier.width(8.dp))
                        Text((c.slot?.label?.let { "$it: " } ?: "Piloto: ") + c.name, fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(if (c.owned) "✓ nv ${c.level ?: "?"}" else "Série ${c.series}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (c.owned) Good else Yellow)
                    }
                }
            }
        }

        // ---------- Desempenho ----------
        item {
            SectionCard("📊 Desempenho") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    WinDonut(report.wins, report.races - report.wins)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BigNumber("${report.races}", "corridas")
                        BigNumber("${(report.recentWinRate * 100).toInt()}%", "últimas 20", if (report.recentWinRate >= 0.5) Good else Warn)
                    }
                }
                report.byTrack.sortedByDescending { it.races }.take(8).forEach { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t.track.name, fontSize = 12.sp, color = Color.White, modifier = Modifier.width(110.dp))
                        MiniBar(t.winRate.toFloat(), if (t.winRate >= 0.5) Good else F1Red, Modifier.weight(1f))
                        Text(" ${t.wins}/${t.races}", fontSize = 11.sp, color = Muted, modifier = Modifier.width(44.dp))
                    }
                }
                report.insights.take(3).forEach { Text("💡 $it", fontSize = 12.sp) }
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
                Text("${fmt.format(Date(r.timestamp))} • grid ${r.gridPositions.joinToString("/").ifEmpty { "?" }} → ${r.finishPositions.joinToString("/").ifEmpty { "?" }} • ${r.source}", fontSize = 11.sp, color = InkMuted)
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

/** Carta que dá para conseguir até a Série 12: lendárias/especiais só contam se você já tem. */
private fun reachable(c: com.leandro.f1clashcoach.core.model.CollectionItem): Boolean =
    c.owned || (c.rarity != com.leandro.f1clashcoach.core.model.Rarity.LEGENDARY && c.rarity != com.leandro.f1clashcoach.core.model.Rarity.SPECIAL && c.series <= 12)
