package com.leandro.f1clashcoach.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leandro.f1clashcoach.capture.LiveStatus
import com.leandro.f1clashcoach.core.ai.CoachPrompts
import com.leandro.f1clashcoach.core.engine.BriefingAction
import com.leandro.f1clashcoach.core.engine.DailyBriefing
import com.leandro.f1clashcoach.core.engine.HistoryAnalyzer
import com.leandro.f1clashcoach.core.engine.TipLevel
import java.time.LocalDate

@Composable
fun HomeScreen(
    vm: MainViewModel,
    modifier: Modifier,
    live: LiveStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenGame: () -> Unit,
    navigate: (Tab) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val data by vm.store.data.collectAsState()
    val settings by vm.settings.state.collectAsState()
    val briefingBusy by vm.briefingBusy.collectAsState()
    val briefingError by vm.briefingError.collectAsState()
    val now = remember(data) { System.currentTimeMillis() }
    val report = remember(data.races) { HistoryAnalyzer.analyze(data.races) }
    val plan = remember(data) { DailyBriefing.build(data.garage, data.races, data.events, data.tyreObservations, data.lastMetaTime, now, data.objectives, data.gp) }
    val today = LocalDate.now().toString()
    val done = if (data.daily.day == today) data.daily.done else emptySet()
    var showAllPlan by remember { mutableStateOf(false) }
    var showFullBriefing by remember { mutableStateOf(false) }

    // A IA trabalha sozinha: briefing do dia gerado ao abrir o app (1x por dia ou com evento novo).
    LaunchedEffect(settings.activeKey, data.events.size) { vm.autoBriefing() }

    fun act(action: BriefingAction, prompt: String?) {
        when (action) {
            BriefingAction.OPEN_GAME -> if (live.running) onOpenGame() else onStart()
            BriefingAction.GARAGE -> navigate(Tab.GARAGE)
            BriefingAction.RACE -> navigate(Tab.RACE)
            BriefingAction.EVOLVE -> navigate(Tab.EVOLVE)
            BriefingAction.AI -> {
                if (prompt == "META") vm.ask(CoachPrompts.WEEKLY_META, webSearch = true)
                else if (prompt != null) vm.ask(prompt, webSearch = true)
                navigate(Tab.AI)
            }
            BriefingAction.NONE -> Unit
        }
    }

    LazyColumn(modifier) {
        // ---------- Coach ao vivo ----------
        item {
            SectionCard("Coach ao vivo") {
                if (live.running) {
                    Text("🟢 Ligado • ${live.screen.label}${live.track?.let { " • ${it.name}" } ?: ""}", fontSize = 14.sp)
                    live.tips.take(3).forEach { t ->
                        Text((if (t.level == TipLevel.URGENT) "🔴 " else if (t.level == TipLevel.IMPORTANT) "🟡 " else "⚪ ") + t.title)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onOpenGame) { Text("Voltar ao jogo") }
                        OutlinedButton(onClick = onStop) { Text("Parar") }
                    }
                } else {
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = F1Red)) {
                        Text("▶ Iniciar Coach e abrir F1 Clash", fontWeight = FontWeight.Bold)
                    }
                    Text("Lê a tela, dá dicas na corrida, reconhece Liga, Grand Prix, Eventos, Exibição e Momentos F1 e preenche a garagem sozinho.", fontSize = 12.sp, color = Muted)
                }
            }
        }

        // ---------- Plano de hoje (automático) ----------
        item {
            SectionCard("📋 Plano de hoje") {
                if (plan.isEmpty()) Text("Tudo em dia! Jogue a liga e mantenha o Coach ligado.", color = Good, fontSize = 13.sp)
                (if (showAllPlan) plan else plan.take(6)).forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = item.action != BriefingAction.NONE) { act(item.action, item.aiPrompt) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(item.icon, fontSize = 18.sp, modifier = Modifier.padding(end = 8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (item.priority == 0) F1Red else Color.Unspecified)
                            Text(item.detail, fontSize = 12.sp, color = Muted)
                        }
                        if (item.action != BriefingAction.NONE) Text("›", fontSize = 20.sp, color = Muted)
                    }
                }
                if (plan.size > 6) TextButton(onClick = { showAllPlan = !showAllPlan }) { Text(if (showAllPlan) "Ver menos" else "Ver mais ${plan.size - 6}") }
            }
        }

        // ---------- Briefing da IA (automático) ----------
        item {
            SectionCard("🤖 Briefing do Coach", action = {
                if (briefingBusy) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                else if (settings.activeKey.isNotBlank()) TextButton(onClick = { vm.autoBriefing(force = true) }) { Text("Atualizar") }
            }) {
                when {
                    settings.activeKey.isBlank() -> Text("Configure a chave de IA em Ajustes ⚙️ e eu monto seu briefing todo dia, sozinho, com o que está ativo no jogo.", fontSize = 13.sp, color = Warn)
                    data.aiBriefing.isBlank() && briefingBusy -> Text("Pesquisando o jogo e analisando sua conta…", fontSize = 13.sp, color = Muted)
                    data.aiBriefing.isBlank() -> Text(briefingError?.let { "⚠️ $it" } ?: "Toque em Atualizar.", fontSize = 13.sp, color = Muted)
                    else -> {
                        val txt = data.aiBriefing
                        SelectionContainer { Text(if (showFullBriefing || txt.length <= 700) txt else txt.take(700) + "…", fontSize = 13.sp) }
                        if (txt.length > 700) TextButton(onClick = { showFullBriefing = !showFullBriefing }) { Text(if (showFullBriefing) "Ver menos" else "Ler tudo") }
                        data.aiBriefingSources.take(4).forEach { s ->
                            val parts = s.split("|", limit = 2)
                            Text("🔗 ${parts[0]}", fontSize = 11.sp, color = Color(0xFF6CB4FF), textDecoration = TextDecoration.Underline,
                                modifier = Modifier.clickable { onOpenUrl(parts.getOrElse(1) { parts[0] }) })
                        }
                        briefingError?.let { Text("⚠️ Última atualização falhou: $it", fontSize = 11.sp, color = Warn) }
                    }
                }
            }
        }

        // ---------- Eventos ativos ----------
        val active = data.events.filter { !it.isOver(now) }.sortedBy { it.endsAt ?: Long.MAX_VALUE }
        if (active.isNotEmpty()) item {
            SectionCard("🎯 Eventos e modos ativos") {
                active.forEach { e ->
                    Column(Modifier.fillMaxWidth().clickable { act(BriefingAction.AI, CoachPrompts.eventAnalysis(e.mode.label, e.name)) }.padding(vertical = 4.dp)) {
                        Text("${e.mode.label}: ${e.name}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        e.remainingMs(now)?.let { Text("⏱ faltam ${DailyBriefing.formatLeft(it)}", fontSize = 12.sp, color = if (it < 86_400_000L) F1Red else Warn) }
                        Text(e.aiAdvice.ifBlank { e.mode.tip }.take(300), fontSize = 12.sp, color = Muted)
                    }
                }
                Text("Abra a tela de cada modo no jogo com o Coach ligado para eu atualizar prazos e analisar.", fontSize = 11.sp, color = Muted)
            }
        }

        // ---------- Conta ----------
        item {
            SectionCard("Sua conta") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    Stat("Série", data.garage.series.toString())
                    Stat("Pontuação", if (data.garage.teamScore > 0) data.garage.teamScore.toString() else "—")
                    Stat("Moedas", formatK(data.garage.coins))
                    Stat("Notas", formatK(data.garage.bucks))
                    Stat("Vitórias", if (report.races == 0) "—" else "${(report.winRate * 100).toInt()}%",
                        if (report.winRate >= 0.55) Good else if (report.races > 0 && report.winRate < 0.4) F1Red else Warn)
                }
            }
        }

        // ---------- Rotina ----------
        item {
            SectionCard("Rotina diária (${done.size}/${MainViewModel.DAILY_ITEMS.size})") {
                MainViewModel.DAILY_ITEMS.forEach { item ->
                    Row(Modifier.fillMaxWidth().clickable { vm.toggleDaily(item) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item in done, onCheckedChange = { vm.toggleDaily(item) })
                        Text(item, fontSize = 13.sp, color = if (item in done) Muted else Color.Unspecified)
                    }
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
