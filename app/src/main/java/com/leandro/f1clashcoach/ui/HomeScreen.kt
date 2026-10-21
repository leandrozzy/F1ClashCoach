package com.leandro.f1clashcoach.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leandro.f1clashcoach.ai.AiClient
import com.leandro.f1clashcoach.ai.Diag
import com.leandro.f1clashcoach.capture.CoachAccessibilityService
import com.leandro.f1clashcoach.capture.LiveStatus
import com.leandro.f1clashcoach.core.ai.CoachPrompts
import com.leandro.f1clashcoach.core.engine.BriefingAction
import com.leandro.f1clashcoach.core.engine.ClubManager
import com.leandro.f1clashcoach.core.engine.DailyBriefing
import com.leandro.f1clashcoach.core.engine.Progression
import com.leandro.f1clashcoach.core.engine.UpgradePlanner
import com.leandro.f1clashcoach.core.model.Catalog
import com.leandro.f1clashcoach.core.model.GameMode
import com.leandro.f1clashcoach.core.model.SeriesCatalog
import kotlinx.coroutines.delay
import java.time.LocalDate

/** Bloco grande clicável do painel (número + rótulo + ícone). */
@Composable
private fun Tile(emoji: String, value: String, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Panel).clickable { onClick() }.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EmojiBadge(emoji, color, 34.dp)
            Spacer(Modifier.weight(1f))
            Text("›", fontSize = 20.sp, color = Muted)
        }
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1)
        Text(label, fontSize = 11.sp, color = Muted, maxLines = 2, lineHeight = 14.sp)
    }
}

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
    onOpenSettings: () -> Unit,
    onOpenClub: () -> Unit = {},
    goAi: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val data by vm.store.data.collectAsState()
    val briefingBusy by vm.briefingBusy.collectAsState()
    val briefingError by vm.briefingError.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    val now = remember(data, tick / 20) { System.currentTimeMillis() }
    val plan = remember(data) { DailyBriefing.build(data.garage, data.races, data.events, data.tyreObservations, data.lastMetaTime, now, data.objectives, data.gp) }
    val series = remember(data.series) { SeriesCatalog.merged(data.series) }
    val prog = remember(data.garage, data.series, data.races, data.flags) { Progression.plan(data.garage, series, data.races, data.flags) }
    val upgrades = remember(data.garage) { UpgradePlanner.plan(data.garage) }
    val club = remember(data.club, data.garage.playerName) { ClubManager.evaluate(data.club, data.garage.playerName, now) }
    val today = LocalDate.now().toString()
    val done = if (data.daily.day == today) data.daily.done else emptySet()
    var showBriefing by remember { mutableStateOf(false) }
    var diagOpen by remember { mutableStateOf(false) }
    val hasAi = remember(tick / 4) { AiClient.hasAnyKey(ctx) }
    val readingOn = remember(tick) { CoachAccessibilityService.isEnabled(ctx) || live.running }
    val gameSeen = data.garage.teamScore > 0 || data.garage.parts.isNotEmpty() || Diag.recognized > 0

    LaunchedEffect(hasAi, data.events.size) { vm.autoBriefing() }

    fun act(action: BriefingAction, prompt: String?) {
        when (action) {
            BriefingAction.OPEN_GAME -> if (readingOn) onOpenGame() else onStart()
            BriefingAction.GARAGE -> navigate(Tab.GARAGE)
            BriefingAction.RACE -> navigate(Tab.RACE)
            BriefingAction.EVOLVE -> navigate(Tab.EVOLVE)
            BriefingAction.AI -> {
                if (prompt == "META") vm.ask(CoachPrompts.WEEKLY_META, webSearch = true) else if (prompt != null) vm.ask(prompt, webSearch = true)
                goAi()
            }
            BriefingAction.NONE -> Unit
        }
    }

    LazyColumn(modifier) {
        // ---------- Topo ----------
        item {
            HeroCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(data.garage.clubName.ifBlank { "F1 Clash Coach" }, fontSize = 12.sp, color = Muted)
                            Text(data.garage.playerName.ifBlank { "Sua equipe" }, fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.White)
                        }
                        val (pill, pc) = when {
                            !readingOn -> "● leitura desligada" to F1Red
                            live.running -> "● lendo o jogo" to Good
                            else -> "● pronto" to Warn
                        }
                        Pill(pill, pc, Modifier.clickable { if (!readingOn) onStart() else diagOpen = true })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        val tmin = prog.target?.scoreMin ?: 1450
                        val score = prog.teamScore
                        GaugeRing(if (score > 0) score.toFloat() / tmin else 0f, if (score > 0) "$score" else "—", "pontuação\nmeta S12 $tmin",
                            if (score >= tmin) Good else if (score >= tmin * 0.8) Warn else F1Red)
                        val decided = data.races.filter { it.won != null }
                        WinDonut(decided.count { it.won == true }, decided.count { it.won == false })
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("S${prog.currentSeries}", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Sky)
                            Text("série", fontSize = 10.sp, color = Muted)
                            Text(data.flags?.let { "🏳 " + formatK(it.toLong()) } ?: "🏳 —", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    // botão principal: abre o F1 Clash (liga a leitura antes, se precisar)
                    Button(
                        onClick = { if (readingOn) onOpenGame() else onStart() },
                        colors = ButtonDefaults.buttonColors(containerColor = F1Red),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                    ) {
                        Text(if (readingOn) "▶  Abrir F1 Clash" else "▶  Ligar leitura e abrir o F1 Clash", fontSize = 17.sp, fontWeight = FontWeight.Black)
                    }
                    SeriesLadder(prog.currentSeries) { n -> series.firstOrNull { it.number == n }?.scoreMin?.let { prog.teamScore >= it } == true }
                    // a ação mais importante agora
                    plan.firstOrNull()?.let { top ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.3f))
                            .clickable { act(top.action, top.aiPrompt) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(top.icon, fontSize = 22.sp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Próximo passo", fontSize = 10.sp, color = Gold, fontWeight = FontWeight.Bold)
                                Text(top.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2)
                            }
                            Text("›", fontSize = 24.sp, color = Color.White)
                        }
                    }
                }
            }
        }

        // ---------- Primeiros passos ----------
        if (!readingOn || !hasAi || !gameSeen) item {
            SectionCard("🚦 Comece por aqui") {
                SetupStep(1, readingOn, "Ligar a leitura do jogo", "Acessibilidade › \"F1 Clash Coach — leitura do jogo\".", "Ligar", onStart)
                SetupStep(2, hasAi, "Conectar uma IA grátis", "Chave do Gemini ou do Groq em Ajustes.", "Configurar", onOpenSettings)
                SetupStep(3, gameSeen, "Passar pelas telas do jogo", "Equipe › Pilotos, Carros e Reforços; Corrida; Clube.", "Abrir jogo", onOpenGame)
            }
        }

        // ---------- Blocos ----------
        item {
            val ready = upgrades.ranked.count { it.canUpgradeNow && it.gain > 0 }
            val boosters = data.garage.boosters.values.sum()
            val gpLeft = data.gp?.roundEndsAt?.let { it - now }?.takeIf { it > 0 }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("⬆", "$ready", "upgrades prontos • 💰 ${formatK(data.garage.coins)}", Good, Modifier.weight(1f)) { navigate(Tab.EVOLVE) }
                    Tile("🧪", "$boosters", "reforços para as corridas", Color(0xFFFF6B9A), Modifier.weight(1f)) { navigate(Tab.RACE) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("🏆", gpLeft?.let { DailyBriefing.formatLeft(it) } ?: "—", data.gp?.name?.let { "Grand Prix: $it" } ?: "abra o Grand Prix no jogo", Gold, Modifier.weight(1f)) { navigate(Tab.RACE) }
                    Tile("👥", "${club.kicks.size}", if (data.club.members.isEmpty()) "abra Clube › Membros" else "no clube para excluir/conversar", Sky, Modifier.weight(1f)) { onOpenClub() }
                }
            }
        }

        // ---------- Minha equipe ----------
        item {
            val eq = remember(data.garage) { (Catalog.driverCollection(data.garage) + Catalog.partCollection(data.garage)).filter { it.equipped } }
            SectionCard("🏎️ Minha equipe", action = { TextButton(onClick = { navigate(Tab.GARAGE) }) { Text("Garagem") } }) {
                if (eq.isEmpty()) Text("Abra Equipe › Pilotos e Carros no jogo: seus pilotos e peças escalados aparecem aqui com foto.", fontSize = 12.sp, color = Muted)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    eq.forEach { Box(Modifier.width(104.dp)) { AssetCard(it) { navigate(Tab.GARAGE) } } }
                }
            }
        }

        // ---------- O que fazer agora ----------
        if (plan.size > 1) item {
            SectionCard("📋 Hoje") {
                plan.drop(1).take(4).forEach { item ->
                    val color = when (item.priority) { 0 -> F1Red; 1 -> Warn; else -> Sky }
                    ActionRow(item.icon, item.title, item.detail.take(90), color,
                        onClick = if (item.action != BriefingAction.NONE) ({ act(item.action, item.aiPrompt) }) else null)
                }
            }
        }

        // ---------- Eventos ----------
        val active = data.events.filter { !it.isOver(now) }.sortedBy { it.endsAt ?: Long.MAX_VALUE }
        if (active.isNotEmpty()) item {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text("🎯 Eventos ativos", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    active.forEach { e ->
                        val left = e.remainingMs(now)
                        val emoji = when (e.mode) { GameMode.GRAND_PRIX -> "🏆"; GameMode.EVENT -> "🎯"; GameMode.LEAGUE -> "🏁"; GameMode.CLUB -> "⭐"; GameMode.SEASON -> "🎟️"; else -> "🎬" }
                        Column(Modifier.width(170.dp).clip(RoundedCornerShape(16.dp)).background(Panel)
                            .clickable { act(BriefingAction.AI, CoachPrompts.eventAnalysis(e.mode.label, e.name)) }.padding(12.dp)) {
                            Text(emoji, fontSize = 22.sp)
                            Text(e.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2)
                            Text(e.mode.label, fontSize = 11.sp, color = Muted)
                            left?.let { Pill("⏱ ${DailyBriefing.formatLeft(it)}", if (it < 86_400_000L) F1Red else Warn, Modifier.padding(top = 6.dp)) }
                        }
                    }
                }
            }
        }

        // ---------- Resultados ----------
        item {
            val last = data.races.filter { it.won != null }.sortedBy { it.timestamp }.takeLast(14).map { it.won == true }
            SectionCard("📊 Últimas corridas") {
                if (last.isEmpty()) Text("Com a leitura ligada, cada resultado entra sozinho.", fontSize = 12.sp, color = Muted)
                else ResultStrip(last)
                data.races.filter { it.review.isNotBlank() }.maxByOrNull { it.timestamp }?.let { r ->
                    ActionRow("🔎", "Por que perdeu a última", r.review.take(120), Warn, onClick = goAi)
                }
            }
        }

        // ---------- Briefing da IA ----------
        item {
            SectionCard("🤖 Briefing do dia", action = {
                if (briefingBusy) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                else if (hasAi) TextButton(onClick = { vm.autoBriefing(force = true) }) { Text("Atualizar") }
            }) {
                when {
                    !hasAi -> ActionRow("🔑", "Conecte uma IA grátis", "Com a chave, eu pesquiso e monto seu plano todo dia.", Warn, onClick = onOpenSettings)
                    data.aiBriefing.isBlank() -> Text(if (briefingBusy) "Analisando sua conta…" else briefingError?.let { "⚠️ $it" } ?: "Toque em Atualizar.", fontSize = 13.sp, color = Muted)
                    else -> {
                        val t = data.aiBriefing
                        SelectionContainer { Text(if (showBriefing) t else t.take(260) + if (t.length > 260) "…" else "", fontSize = 13.sp, lineHeight = 19.sp) }
                        if (t.length > 260) TextButton(onClick = { showBriefing = !showBriefing }) { Text(if (showBriefing) "Ver menos" else "Ler tudo") }
                    }
                }
            }
        }

        // ---------- Rotina ----------
        item {
            SectionCard("✅ Rotina diária") {
                ProgressLine(done.size.toFloat() / MainViewModel.DAILY_ITEMS.size, Good)
                MainViewModel.DAILY_ITEMS.forEach { item ->
                    Row(Modifier.fillMaxWidth().clickable { vm.toggleDaily(item) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item in done, onCheckedChange = { vm.toggleDaily(item) })
                        Text(item, fontSize = 13.sp, color = if (item in done) Muted else Color.White)
                    }
                }
            }
        }
        item {
            TextButton(onClick = { diagOpen = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("📡 Diagnóstico da leitura (${Diag.shots} fotos • ${Diag.recognized} telas)", fontSize = 12.sp, color = Muted) }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }

    if (diagOpen) {
        val text = remember(tick) { Diag.report(ctx) }
        AlertDialog(
            onDismissRequest = { diagOpen = false },
            containerColor = Panel,
            title = { Text("Diagnóstico da leitura") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("Se algo não funcionar, toque em Copiar e me mande o texto.", fontSize = 12.sp, color = Muted)
                    if (!readingOn) Button(onClick = { diagOpen = false; onStart() }, colors = ButtonDefaults.buttonColors(containerColor = F1Red)) { Text("Ligar leitura") }
                    SelectionContainer { Text(text, fontSize = 11.sp, lineHeight = 15.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Diagnóstico F1 Coach", text))
                    vm.toast("Diagnóstico copiado")
                }) { Text("Copiar") }
            },
            dismissButton = { TextButton(onClick = { diagOpen = false }) { Text("Fechar") } },
        )
    }
}

fun formatK(v: Long): String = when {
    v >= 1_000_000_000 -> "%.1fB".format(v / 1_000_000_000.0)
    v >= 1_000_000 -> "%.1fM".format(v / 1_000_000.0)
    v >= 10_000 -> "%.0fk".format(v / 1000.0)
    else -> v.toString()
}
