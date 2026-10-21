package com.leandro.f1clashcoach.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leandro.f1clashcoach.ai.AiClient
import com.leandro.f1clashcoach.ai.Diag
import com.leandro.f1clashcoach.capture.CoachAccessibilityService
import com.leandro.f1clashcoach.capture.LiveStatus
import com.leandro.f1clashcoach.core.ai.CoachPrompts
import com.leandro.f1clashcoach.core.engine.BriefingAction
import com.leandro.f1clashcoach.core.engine.DailyBriefing
import com.leandro.f1clashcoach.core.engine.HistoryAnalyzer
import com.leandro.f1clashcoach.core.engine.Progression
import com.leandro.f1clashcoach.core.engine.TipLevel
import com.leandro.f1clashcoach.core.model.GameMode
import com.leandro.f1clashcoach.core.model.SeriesCatalog
import kotlinx.coroutines.delay
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
    onOpenSettings: () -> Unit,
    onOpenClub: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val data by vm.store.data.collectAsState()
    val briefingBusy by vm.briefingBusy.collectAsState()
    val briefingError by vm.briefingError.collectAsState()
    // Atualiza o painel de leitura (Diag) a cada segundo.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val now = remember(data, tick / 30) { System.currentTimeMillis() }
    val report = remember(data.races) { HistoryAnalyzer.analyze(data.races) }
    val plan = remember(data) { DailyBriefing.build(data.garage, data.races, data.events, data.tyreObservations, data.lastMetaTime, now, data.objectives, data.gp) }
    val series = remember(data.series) { SeriesCatalog.merged(data.series) }
    val prog = remember(data.garage, data.series, data.races, data.flags) { Progression.plan(data.garage, series, data.races, data.flags) }
    val today = LocalDate.now().toString()
    val done = if (data.daily.day == today) data.daily.done else emptySet()
    var showAllPlan by remember { mutableStateOf(false) }
    var showFullBriefing by remember { mutableStateOf(false) }
    var diagOpen by remember { mutableStateOf(false) }

    val hasAi = remember(tick / 3) { AiClient.hasAnyKey(ctx) }
    val readingOn = remember(tick) { CoachAccessibilityService.isEnabled(ctx) || live.running }
    val gameSeen = data.garage.teamScore > 0 || data.garage.parts.isNotEmpty() || Diag.recognized > 0

    // A IA trabalha sozinha: briefing do dia gerado ao abrir o app (1x por dia ou com evento novo).
    LaunchedEffect(hasAi, data.events.size) { vm.autoBriefing() }

    fun act(action: BriefingAction, prompt: String?) {
        when (action) {
            BriefingAction.OPEN_GAME -> if (readingOn) onOpenGame() else onStart()
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
        // ---------- Topo: equipe, pontuação x S12, vitórias ----------
        item {
            HeroCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(data.garage.clubName.ifBlank { "Sua equipe" }, fontSize = 13.sp, color = Muted)
                            Text(data.garage.playerName.ifBlank { "F1 Clash Coach" }, fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.White)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Pill("Série ${prog.currentSeries}", Sky)
                                Pill("Meta: S12 Ouro", Gold)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        val tmin = prog.target?.scoreMin ?: 1450
                        val score = prog.teamScore
                        GaugeRing(if (score > 0) score.toFloat() / tmin else 0f, if (score > 0) score.toString() else "—", "pontuação\nmeta $tmin",
                            if (score >= tmin) Good else if (score >= tmin * 0.8) Warn else F1Red)
                        val decided = data.races.filter { it.won != null }
                        WinDonut(decided.count { it.won == true }, decided.count { it.won == false })
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(data.flags?.let { formatK(it.toLong()) } ?: "—", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.White)
                            Text("🏳 bandeiras", fontSize = 11.sp, color = Muted)
                            Text(prog.starsPerWeekNow?.let { "★$it/sem" } ?: "", fontSize = 12.sp, color = Gold)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BigNumber(formatK(data.garage.coins), "moedas", Gold, Modifier.weight(1f))
                        BigNumber(formatK(data.garage.bucks), "notas", Good, Modifier.weight(1f))
                        BigNumber("${data.garage.parts.size}", "peças", Color.White, Modifier.weight(1f))
                        BigNumber("${data.garage.drivers.size}", "pilotos", Color.White, Modifier.weight(1f))
                    }
                }
            }
        }

        // ---------- Minha equipe (cartas escaladas) ----------
        item {
            val eq = remember(data.garage) {
                val d = com.leandro.f1clashcoach.core.model.Catalog.driverCollection(data.garage).filter { it.equipped }
                val p = com.leandro.f1clashcoach.core.model.Catalog.partCollection(data.garage).filter { it.equipped }
                (d + p)
            }
            SectionCard("🏎️ Minha equipe", action = { TextButton(onClick = { navigate(Tab.GARAGE) }) { Text("Garagem") } }) {
                if (eq.isEmpty()) Text("Abra Equipe › Carros e Pilotos no jogo: suas cartas escaladas aparecem aqui.", fontSize = 12.sp, color = Muted)
                eq.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { AssetCard(it) { navigate(Tab.GARAGE) } } }
                        repeat(3 - row.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        // ---------- Primeiros passos (some quando tudo estiver pronto) ----------
        if (!readingOn || !hasAi || !gameSeen) item {
            SectionCard("🚦 Comece por aqui") {
                SetupStep(1, readingOn, "Ligar a leitura do jogo",
                    if (CoachAccessibilityService.supported) "Em Acessibilidade, ative \"F1 Clash Coach — leitura do jogo\". Só lê enquanto o F1 Clash está aberto."
                    else "Permita sobrepor e gravar a tela.", "Ligar", onStart)
                SetupStep(2, hasAi, "Conectar uma IA grátis", "Cole uma chave do Gemini (aistudio.google.com) ou do Groq (console.groq.com).", "Configurar", onOpenSettings)
                SetupStep(3, gameSeen, "Abrir o F1 Clash e passar pelas telas", "Garagem (carros e pilotos), Corrida, Grand Prix, Eventos e Duelos: eu leio tudo sozinho.", "Abrir jogo", onOpenGame)
            }
        }

        // ---------- Leitura ao vivo ----------
        item {
            val recent = Diag.lastScreenAt > 0 && System.currentTimeMillis() - Diag.lastScreenAt < 60_000
            SectionCard("📡 Leitura do jogo", action = { TextButton(onClick = { diagOpen = true }) { Text("Diagnóstico") } }) {
                val (pill, color) = when {
                    !readingOn -> "Desligada" to F1Red
                    live.running && recent -> "Lendo o jogo" to Good
                    live.running -> "Ligada • aguardando tela" to Warn
                    else -> "Ligada • abra o F1 Clash" to Warn
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("● $pill", color)
                    if (Diag.lastScreen.isNotBlank()) Text("Última tela: ${Diag.lastScreen}", fontSize = 12.sp, color = Muted)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BigNumber("${Diag.shots}", "fotos", Color.White, Modifier.weight(1f))
                    BigNumber("${Diag.recognized}", "telas lidas", Good, Modifier.weight(1f))
                    BigNumber("${live.events.size}", "atualizações", Sky, Modifier.weight(1f))
                }
                live.tips.take(3).forEach { t ->
                    ActionRow(if (t.level == TipLevel.URGENT) "🔴" else if (t.level == TipLevel.IMPORTANT) "🟡" else "⚪", t.title, t.detail,
                        if (t.level == TipLevel.URGENT) F1Red else Warn)
                }
                live.events.take(2).forEach { Text("• $it", fontSize = 12.sp, color = Muted) }
                Diag.lastError?.takeIf { System.currentTimeMillis() - Diag.lastErrorAt < 10 * 60_000 }?.let {
                    Text("⚠️ $it", fontSize = 12.sp, color = Warn)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (readingOn) {
                        Button(onClick = onOpenGame, colors = ButtonDefaults.buttonColors(containerColor = F1Red), modifier = Modifier.weight(1f)) { Text("🏎️ Abrir F1 Clash", fontWeight = FontWeight.Bold) }
                        if (!CoachAccessibilityService.supported) OutlinedButton(onClick = onStop) { Text("Parar") }
                    } else {
                        Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = F1Red), modifier = Modifier.fillMaxWidth()) { Text("▶ Ligar leitura e abrir o jogo", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        // ---------- Plano de hoje (automático) ----------
        item {
            SectionCard("📋 O que fazer agora") {
                if (plan.isEmpty()) Text("Tudo em dia! Jogue os duelos com a leitura ligada.", color = Good, fontSize = 13.sp)
                (if (showAllPlan) plan else plan.take(5)).forEach { item ->
                    val color = when (item.priority) { 0 -> F1Red; 1 -> Warn; else -> Sky }
                    ActionRow(item.icon, item.title, item.detail, color,
                        onClick = if (item.action != BriefingAction.NONE) ({ act(item.action, item.aiPrompt) }) else null)
                }
                if (plan.size > 5) TextButton(onClick = { showAllPlan = !showAllPlan }) { Text(if (showAllPlan) "Ver menos" else "Ver mais ${plan.size - 5}") }
            }
        }

        // ---------- Onde gastar moedas ----------
        item {
            val up = remember(data.garage) { com.leandro.f1clashcoach.core.engine.UpgradePlanner.plan(data.garage) }
            SectionCard("💰 Onde gastar moedas") {
                if (up.doNow.isEmpty() && up.collectCards.isEmpty()) Text("Abra Equipe › Carros e Pilotos no jogo para eu ler níveis, cartas e custos.", fontSize = 12.sp, color = Muted)
                up.doNow.take(4).forEachIndexed { i, a ->
                    ActionRow("${i + 1}", "Evoluir ${a.name}", "${a.kind} nv ${a.level} • ${a.reason}", Good, onClick = { navigate(Tab.EVOLVE) })
                }
                if (up.doNow.isNotEmpty()) Text("Sobram ${formatK(up.coinsLeft)} moedas depois disso.", fontSize = 12.sp, color = Muted)
                if (up.collectCards.isNotEmpty()) {
                    Text("Juntar cartas de (caixotes, loja, clube):", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    up.collectCards.take(3).forEach { a -> Text("• ${a.name}: ${a.reason}", fontSize = 12.sp, color = Muted) }
                }
            }
        }

        // ---------- Grand Prix da semana ----------
        item {
            val gpPlan = remember(data.garage, data.gp) { com.leandro.f1clashcoach.core.engine.GpPlanner.plan(data.garage, data.gp) }
            SectionCard("🏆 Grand Prix da semana") {
                data.gp?.let { gp ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(gp.name, Sky)
                        gp.roundEndsAt?.let { Pill("⏱ " + DailyBriefing.formatLeft(it - now), if (it - now < 86_400_000L) F1Red else Warn) }
                    }
                }
                gpPlan.lines.forEach { Text(it, fontSize = 13.sp, lineHeight = 18.sp) }
            }
        }

        // ---------- Clube ----------
        item {
            val clubRep = remember(data.club, data.garage.playerName) { com.leandro.f1clashcoach.core.engine.ClubManager.evaluate(data.club, data.garage.playerName, now) }
            SectionCard("👥 Clube ${data.garage.clubName}".trim(), action = { TextButton(onClick = onOpenClub) { Text("Abrir") } }) {
                if (data.club.members.isEmpty()) ActionRow("👥", "Leia os membros", "Abra Clube › Membros no jogo e role a lista. Eu aviso quem excluir e quando.", Sky, onClick = onOpenClub)
                else {
                    Text("${data.club.memberCount ?: data.club.members.size}/50 membros • ${clubRep.kicks.size} para excluir/conversar", fontSize = 13.sp)
                    clubRep.kicks.take(3).forEach { k -> ActionRow(if (k.urgency == 2) "🔴" else if (k.urgency == 1) "🟡" else "💬", k.name, k.reason, if (k.urgency == 2) F1Red else Warn, onClick = onOpenClub) }
                    Text("⏰ ${clubRep.whenToAct}", fontSize = 12.sp, color = Muted)
                }
            }
        }

        // ---------- Briefing da IA (automático) ----------
        item {
            SectionCard("🤖 Briefing do Coach", action = {
                if (briefingBusy) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                else if (hasAi) TextButton(onClick = { vm.autoBriefing(force = true) }) { Text("Atualizar") }
            }) {
                when {
                    !hasAi -> ActionRow("🔑", "Conecte uma IA grátis", "Com a chave, eu pesquiso o jogo e monto seu plano sozinho todo dia.", Warn, onClick = onOpenSettings)
                    data.aiBriefing.isBlank() && briefingBusy -> Text("Pesquisando o jogo e analisando sua conta…", fontSize = 13.sp, color = Muted)
                    data.aiBriefing.isBlank() -> Text(briefingError?.let { "⚠️ $it" } ?: "Toque em Atualizar.", fontSize = 13.sp, color = if (briefingError != null) Warn else Muted)
                    else -> {
                        val txt = data.aiBriefing
                        SelectionContainer { Text(if (showFullBriefing || txt.length <= 700) txt else txt.take(700) + "…", fontSize = 14.sp, lineHeight = 20.sp) }
                        if (txt.length > 700) TextButton(onClick = { showFullBriefing = !showFullBriefing }) { Text(if (showFullBriefing) "Ver menos" else "Ler tudo") }
                        data.aiBriefingSources.take(4).forEach { s ->
                            val parts = s.split("|", limit = 2)
                            Text("🔗 ${parts[0]}", fontSize = 11.sp, color = Sky, textDecoration = TextDecoration.Underline,
                                modifier = Modifier.clickable { onOpenUrl(parts.getOrElse(1) { parts[0] }) })
                        }
                        briefingError?.let { Text("⚠️ Última atualização falhou: $it", fontSize = 11.sp, color = Warn) }
                    }
                }
            }
        }

        // ---------- Rumo à Série 12 ----------
        item {
            SectionCard("🏆 Rumo ao Grand Slam de Ouro") {
                SeriesLadder(prog.currentSeries) { n -> series.firstOrNull { it.number == n }?.scoreMin?.let { prog.teamScore >= it } == true }
                prog.steps.forEach { st ->
                    val need = st.series.flagsToUnlock
                    val have = data.flags
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text("S${st.series.number} ${st.series.name}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(st.series.prizeStars?.let { "★$it" } ?: "", fontSize = 12.sp, color = Gold)
                        }
                        if (need != null && need > 0 && have != null) ProgressLine(have.toFloat() / need, if (have >= need) Good else Sky)
                        Text(listOfNotNull(
                            st.flagsMissing?.let { if (it > 0) "faltam $it 🏳" else "bandeiras ok" },
                            st.scoreGap?.let { if (it > 0) "+$it pontuação" else "pontuação ok" },
                            st.estRaces?.let { "~$it corridas" },
                        ).joinToString(" • "), fontSize = 11.sp, color = Muted)
                    }
                }
                prog.advice.take(3).forEach { Text("💡 $it", fontSize = 12.sp, lineHeight = 17.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.ask(CoachPrompts.TARGET_META, webSearch = true); navigate(Tab.AI) }, modifier = Modifier.weight(1f)) { Text("🌐 Melhores p/ S12", fontSize = 12.sp) }
                    OutlinedButton(onClick = { vm.ask(CoachPrompts.CLUB_PLAN, webSearch = true); navigate(Tab.AI) }, modifier = Modifier.weight(1f)) { Text("⭐ Plano do clube", fontSize = 12.sp) }
                }
                if (data.targetMeta.isNotBlank()) {
                    Text("Última pesquisa S12:", fontSize = 11.sp, color = Muted)
                    Text(data.targetMeta.take(500) + if (data.targetMeta.length > 500) "…" else "", fontSize = 12.sp)
                }
            }
        }

        // ---------- Eventos ativos ----------
        val active = data.events.filter { !it.isOver(now) }.sortedBy { it.endsAt ?: Long.MAX_VALUE }
        item {
            SectionCard("🎯 Eventos e modos ativos") {
                if (active.isEmpty()) Text("Nenhum evento lido ainda. Abra Grand Prix, Eventos e Momentos F1 no jogo com a leitura ligada.", fontSize = 12.sp, color = Muted)
                active.forEach { e ->
                    val left = e.remainingMs(now)
                    val emoji = when (e.mode) { GameMode.GRAND_PRIX -> "🏆"; GameMode.EVENT -> "🎯"; GameMode.LEAGUE -> "🏁"; GameMode.CLUB -> "⭐"; GameMode.SEASON -> "🎟️"; else -> "🎬" }
                    ActionRow(emoji, "${e.mode.label}: ${e.name}",
                        (left?.let { "⏱ faltam ${DailyBriefing.formatLeft(it)}\n" } ?: "") + e.aiAdvice.ifBlank { e.mode.tip }.take(220),
                        if (left != null && left < 86_400_000L) F1Red else Sky,
                        onClick = { act(BriefingAction.AI, CoachPrompts.eventAnalysis(e.mode.label, e.name)) })
                }
            }
        }

        // ---------- Resultados ----------
        item {
            SectionCard("📊 Suas corridas") {
                val last = data.races.filter { it.won != null }.sortedBy { it.timestamp }.takeLast(12).map { it.won == true }
                if (last.isEmpty()) Text("Ainda sem corridas. Com a leitura ligada, cada resultado é salvo sozinho.", fontSize = 12.sp, color = Muted)
                else {
                    ResultStrip(last)
                    Text("${report.races} corridas • ${(report.winRate * 100).toInt()}% vitórias • últimas 20: ${(report.recentWinRate * 100).toInt()}%", fontSize = 12.sp, color = Muted)
                    report.insights.take(2).forEach { Text("💡 $it", fontSize = 12.sp) }
                    remember(data.races) { com.leandro.f1clashcoach.core.engine.TrackPlaybook.build(data.races) }.filter { it.bestStrategy != null }.take(4).forEach { tp ->
                        Text("🏁 ${tp.trackName}: ${tp.wins}/${tp.races} • melhor ${tp.bestStrategy} (${tp.bestStrategyWins}/${tp.bestStrategyRaces})", fontSize = 12.sp)
                    }
                    data.races.filter { it.review.isNotBlank() }.maxByOrNull { it.timestamp }?.let { r ->
                        Text("🔎 Última derrota: ${r.review.take(280)}", fontSize = 12.sp, color = Muted)
                    }
                }
            }
        }

        // ---------- Rotina ----------
        item {
            SectionCard("✅ Rotina diária") {
                ProgressLine(done.size.toFloat() / MainViewModel.DAILY_ITEMS.size, Good)
                Text("${done.size} de ${MainViewModel.DAILY_ITEMS.size} feitos hoje", fontSize = 12.sp, color = Muted)
                MainViewModel.DAILY_ITEMS.forEach { item ->
                    Row(Modifier.fillMaxWidth().clickable { vm.toggleDaily(item) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item in done, onCheckedChange = { vm.toggleDaily(item) })
                        Text(item, fontSize = 13.sp, color = if (item in done) Muted else Color.Unspecified)
                    }
                }
            }
        }
        item { Text(" ", Modifier.padding(8.dp)) }
    }

    if (diagOpen) {
        val text = remember(tick) { Diag.report(ctx) }
        AlertDialog(
            onDismissRequest = { diagOpen = false },
            title = { Text("Diagnóstico da leitura") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("Se algo não funcionar, toque em Copiar e me mande o texto.", fontSize = 12.sp, color = Muted)
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
    v >= 1_000_000 -> "%.1fM".format(v / 1_000_000.0)
    v >= 10_000 -> "%.0fk".format(v / 1000.0)
    else -> v.toString()
}
