package com.leandro.f1clashcoach.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leandro.f1clashcoach.capture.LiveStatus
import com.leandro.f1clashcoach.core.ai.CoachPrompts
import com.leandro.f1clashcoach.core.engine.HistoryAnalyzer
import com.leandro.f1clashcoach.core.engine.TipLevel
import com.leandro.f1clashcoach.core.engine.UpgradePlanner
import java.time.LocalDate

@Composable
fun HomeScreen(
    vm: MainViewModel,
    modifier: Modifier,
    live: LiveStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenGame: () -> Unit,
    goAi: () -> Unit,
) {
    val data by vm.store.data.collectAsState()
    val report = remember(data.races) { HistoryAnalyzer.analyze(data.races) }
    val plan = remember(data.garage) { UpgradePlanner.plan(data.garage) }
    val today = LocalDate.now().toString()
    val done = if (data.daily.day == today) data.daily.done else emptySet()

    LazyColumn(modifier) {
        item {
            SectionCard("Coach ao vivo") {
                if (live.running) {
                    Text("🟢 Ligado • tela: ${live.screen.label}${live.track?.let { " • ${it.name}" } ?: ""}", fontSize = 14.sp)
                    Text("${live.framesRead} leituras feitas", fontSize = 12.sp, color = Muted)
                    live.tips.take(3).forEach { t ->
                        Text((if (t.level == TipLevel.URGENT) "🔴 " else if (t.level == TipLevel.IMPORTANT) "🟡 " else "⚪ ") + t.title)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onOpenGame) { Text("Voltar ao jogo") }
                        OutlinedButton(onClick = onStop) { Text("Parar") }
                    }
                } else {
                    Text("Liga a leitura da tela + painel flutuante e abre o F1 Clash. Durante a corrida ele avisa quando parar, trocar pneu, atacar ou conservar. Nas telas de peças/pilotos ele preenche sua garagem sozinho.", fontSize = 13.sp, color = Muted)
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = F1Red)) {
                        Text("▶ Iniciar Coach e abrir F1 Clash", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            SectionCard("Sua conta") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    Stat("Série", data.garage.series.toString())
                    Stat("Troféus", data.garage.trophies.toString())
                    Stat("Moedas", formatK(data.garage.coins))
                    Stat("Vitórias", if (report.races == 0) "—" else "${(report.winRate * 100).toInt()}%",
                        if (report.winRate >= 0.55) Good else if (report.races > 0 && report.winRate < 0.4) F1Red else Warn)
                }
                if (data.garage.parts.isEmpty() && data.garage.drivers.isEmpty()) {
                    Text("Comece cadastrando sua garagem: ligue o Coach e abra suas peças no jogo, ou importe prints na aba Garagem.", fontSize = 13.sp, color = Warn)
                }
                report.insights.take(2).forEach { Text("💡 $it", fontSize = 13.sp) }
            }
        }
        if (plan.doNow.isNotEmpty()) item {
            SectionCard("Evolua agora") {
                plan.doNow.take(3).forEach { Text("⬆ ${it.name} (${it.kind}) — ${it.reason}", fontSize = 13.sp) }
                Text("Sobram ${formatK(plan.coinsLeft)} moedas", fontSize = 12.sp, color = Muted)
            }
        }
        item {
            SectionCard("Rotina de hoje (${done.size}/${MainViewModel.DAILY_ITEMS.size})") {
                MainViewModel.DAILY_ITEMS.forEach { item ->
                    Row(Modifier.fillMaxWidth().clickable { vm.toggleDaily(item) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item in done, onCheckedChange = { vm.toggleDaily(item) })
                        Text(item, fontSize = 13.sp, color = if (item in done) Muted else androidx.compose.ui.graphics.Color.Unspecified)
                    }
                }
            }
        }
        item {
            SectionCard("Atalhos da IA") {
                OutlinedButton(onClick = { vm.ask(CoachPrompts.WEEKLY_META, webSearch = true); goAi() }, modifier = Modifier.fillMaxWidth()) { Text("🌐 Meta desta semana para minha série") }
                OutlinedButton(onClick = { vm.ask(CoachPrompts.EVOLUTION_PLAN); goAi() }, modifier = Modifier.fillMaxWidth()) { Text("📈 Plano de evolução de 7 dias") }
                if (data.lastMeta.isNotBlank()) {
                    Text("Último meta salvo:", fontSize = 12.sp, color = Muted)
                    Text(data.lastMeta.take(400) + if (data.lastMeta.length > 400) "…" else "", fontSize = 13.sp)
                }
            }
        }
        if (live.events.isNotEmpty()) item {
            SectionCard("Atividade recente") { live.events.take(6).forEach { Text("• $it", fontSize = 12.sp, color = Muted) } }
        }
        item { Text(" ", Modifier.padding(8.dp)) }
    }
}

fun formatK(v: Long): String = when {
    v >= 1_000_000 -> "%.1fM".format(v / 1_000_000.0)
    v >= 10_000 -> "%.0fk".format(v / 1000.0)
    else -> v.toString()
}
