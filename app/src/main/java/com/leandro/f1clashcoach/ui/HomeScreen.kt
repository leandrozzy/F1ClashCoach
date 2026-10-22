package com.leandro.f1clashcoach.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leandro.f1clashcoach.ai.AiClient
import com.leandro.f1clashcoach.ai.Diag
import com.leandro.f1clashcoach.capture.CoachAccessibilityService
import com.leandro.f1clashcoach.capture.LiveStatus
import com.leandro.f1clashcoach.core.ai.CoachPrompts
import com.leandro.f1clashcoach.core.engine.AdviceCard
import com.leandro.f1clashcoach.core.engine.AdviceTarget
import com.leandro.f1clashcoach.core.engine.Advisor
import com.leandro.f1clashcoach.core.engine.DailyBriefing
import com.leandro.f1clashcoach.core.engine.Progression
import com.leandro.f1clashcoach.core.model.Catalog
import com.leandro.f1clashcoach.core.model.GameMode
import com.leandro.f1clashcoach.core.model.SeriesCatalog
import kotlinx.coroutines.delay

/** Cartão de conselho automático: ícone, título e 1–4 linhas. Sem dado, diz o que abrir no jogo. */
@Composable
private fun AdviceView(c: AdviceCard, onClick: () -> Unit) {
    val accent = when { c.missing != null -> InkMuted; c.priority == 0 -> F1Red; c.priority == 1 -> Mint; else -> Sky }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Panel)
            .clickable { onClick() }.padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(4.dp).height(40.dp).clip(RoundedCornerShape(50)).background(accent))
        Spacer(Modifier.width(10.dp))
        Text(c.icon, fontSize = 22.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(c.title, fontSize = 14.sp, fontWeight = FontWeight.Black, color = Color.White)
            if (c.missing != null) Text("👉 ${c.missing}", fontSize = 12.sp, color = Yellow)
            c.lines.forEach { Text(it, fontSize = 12.sp, color = Color(0xFFD5DAEA), lineHeight = 16.sp) }
        }
        Text("›", fontSize = 22.sp, color = Muted)
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
    val series = remember(data.series) { SeriesCatalog.merged(data.series) }
    val prog = remember(data.garage, data.series, data.races, data.flags) { Progression.plan(data.garage, series, data.races, data.flags) }
    val cards = remember(data, now) {
        Advisor.cards(data.garage, data.races, data.events, data.objectives, data.gp, data.club, series, data.flags, data.tyreObservations, data.lastMetaTime, now)
    }
    var showBriefing by remember { mutableStateOf(false) }
    var diagOpen by remember { mutableStateOf(false) }
    val hasAi = remember(tick / 4) { AiClient.hasAnyKey(ctx) }
    val readingOn = remember(tick) { CoachAccessibilityService.isEnabled(ctx) || live.running }

    // A IA trabalha sozinha: análise do dia ao abrir (1x por dia ou com evento novo).
    LaunchedEffect(hasAi, data.events.size) { vm.autoBriefing() }

    fun go(t: AdviceTarget) {
        when (t) {
            AdviceTarget.GAME -> if (readingOn) onOpenGame() else onStart()
            AdviceTarget.GARAGE -> navigate(Tab.GARAGE)
            AdviceTarget.EVOLVE -> navigate(Tab.EVOLVE)
            AdviceTarget.RACE -> navigate(Tab.RACE)
            AdviceTarget.CLUB -> onOpenClub()
            AdviceTarget.AI -> goAi()
            AdviceTarget.NONE -> Unit
        }
    }

    LazyColumn(modifier) {
        // ---------- Topo: equipe e botão CORRIDA (como no jogo) ----------
        item {
            Column(
                Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(18.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF232945), Navy))).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(data.garage.clubName.ifBlank { "F1 CLASH COACH" }.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Black, color = Muted)
                        Text(data.garage.playerName.ifBlank { "Sua equipe" }, fontSize = 20.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = Color.White)
                    }
                    val (pill, pc) = when { !readingOn -> "● leitura desligada" to F1Red; live.running -> "● lendo o jogo" to Mint; else -> "● pronto" to Yellow }
                    Pill(pill, pc, Modifier.clickable { if (!readingOn) onStart() else diagOpen = true })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BigNumber(if (prog.teamScore > 0) "${prog.teamScore}" else "—", "pontuação", Color.White, Modifier.weight(1f))
                    BigNumber("S${prog.currentSeries}", "série", Sky, Modifier.weight(1f))
                    BigNumber(data.flags?.let { formatK(it.toLong()) } ?: "—", "bandeiras", Color.White, Modifier.weight(1f))
                    BigNumber(formatK(data.garage.coins), "moedas", Gold, Modifier.weight(1f))
                }
                val tmin = prog.target?.scoreMin ?: 1450
                Column {
                    Row { Text("Rumo à S12 (faixa $tmin+)", fontSize = 11.sp, color = Muted, modifier = Modifier.weight(1f)); Text("${prog.teamScore}/$tmin", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold) }
                    ProgressLine(if (prog.teamScore > 0) prog.teamScore.toFloat() / tmin else 0f, if (prog.teamScore >= tmin) Mint else Yellow)
                }
                Button(
                    onClick = { if (readingOn) onOpenGame() else onStart() },
                    colors = ButtonDefaults.buttonColors(containerColor = F1Red),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(56.dp).border(2.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
                ) {
                    Text(if (readingOn) "CORRIDA" else "LIGAR LEITURA E JOGAR", fontSize = 20.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = Color.White)
                }
                if (!readingOn) Text("Toque acima e ative \"F1 Clash Coach — leitura do jogo\" na Acessibilidade. Depois é só jogar: eu leio tudo sozinho.", fontSize = 11.sp, color = Muted)
                else if (!hasAi) Text("Opcional: conecte uma IA grátis em Ajustes (⚙️) para análises extras.", fontSize = 11.sp, color = Muted)
            }
        }

        // ---------- Conselhos automáticos ----------
        item { PageTitle("O que fazer agora", trailing = "automático") }
        items(cards, key = { it.id }) { c -> AdviceView(c) { go(c.target) } }

        // ---------- Minha equipe ----------
        item {
            val eq = remember(data.garage) { (Catalog.driverCollection(data.garage) + Catalog.partCollection(data.garage)).filter { it.equipped } }
            if (eq.isNotEmpty()) Column {
                PageTitle("Minha equipe", trailing = "Garagem ›")
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    eq.forEach { Box(Modifier.width(78.dp)) { AssetCard(it) { navigate(Tab.GARAGE) } } }
                }
            }
        }

        // ---------- Eventos ativos ----------
        val active = data.events.filter { !it.isOver(now) }.sortedBy { it.endsAt ?: Long.MAX_VALUE }
        if (active.isNotEmpty()) item {
            Column {
                PageTitle("Eventos ativos")
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    active.forEach { e ->
                        val left = e.remainingMs(now)
                        val emoji = when (e.mode) { GameMode.GRAND_PRIX -> "🏆"; GameMode.EVENT -> "🎯"; GameMode.LEAGUE -> "🏁"; GameMode.CLUB -> "⭐"; GameMode.SEASON -> "🎟️"; else -> "🎬" }
                        Column(Modifier.width(160.dp).clip(RoundedCornerShape(14.dp)).background(Panel).padding(12.dp)) {
                            Text(emoji, fontSize = 20.sp)
                            Text(e.name, fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color.White, maxLines = 2)
                            Text(e.mode.label, fontSize = 10.sp, color = Muted)
                            left?.let { Pill("⏱ ${DailyBriefing.formatLeft(it)}", if (it < 86_400_000L) F1Red else Yellow, Modifier.padding(top = 6.dp)) }
                        }
                    }
                }
            }
        }

        // ---------- Análise da IA (automática) ----------
        if (hasAi) item {
            SectionCard("🤖 Análise do dia (IA)", action = {
                if (briefingBusy) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                else TextButton(onClick = { vm.autoBriefing(force = true) }) { Text("Atualizar", color = Yellow) }
            }) {
                val t = data.aiBriefing
                when {
                    t.isBlank() -> Text(if (briefingBusy) "Analisando sua conta…" else briefingError?.let { "⚠️ $it" } ?: "Toque em Atualizar.", fontSize = 13.sp, color = Muted)
                    else -> {
                        SelectionContainer { Text(if (showBriefing) t else t.take(320) + if (t.length > 320) "…" else "", fontSize = 13.sp, lineHeight = 19.sp) }
                        if (t.length > 320) TextButton(onClick = { showBriefing = !showBriefing }) { Text(if (showBriefing) "Ver menos" else "Ler tudo", color = Yellow) }
                    }
                }
            }
        }

        // ---------- Resultados ----------
        item {
            val last = data.races.filter { it.won != null }.sortedBy { it.timestamp }.takeLast(14).map { it.won == true }
            if (last.isNotEmpty()) Column {
                PageTitle("Últimas corridas", trailing = "${last.count { it }}/${last.size} vitórias")
                Box(Modifier.padding(horizontal = 16.dp)) { ResultStrip(last) }
            }
        }
        item {
            TextButton(onClick = { diagOpen = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("📡 Diagnóstico da leitura (${Diag.shots} fotos • ${Diag.recognized} telas)", fontSize = 12.sp, color = InkMuted)
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }

    if (diagOpen) {
        val text = remember(tick) { Diag.report(ctx) }
        AlertDialog(
            onDismissRequest = { diagOpen = false },
            containerColor = Panel,
            title = { Text("Diagnóstico da leitura", color = Color.White) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("Se algo não funcionar, toque em Copiar e me mande o texto.", fontSize = 12.sp, color = Muted)
                    if (!readingOn) Button(onClick = { diagOpen = false; onStart() }, colors = ButtonDefaults.buttonColors(containerColor = F1Red)) { Text("Ligar leitura") }
                    SelectionContainer { Text(text, fontSize = 11.sp, lineHeight = 15.sp, color = Color.White) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Diagnóstico F1 Coach", text))
                    vm.toast("Diagnóstico copiado")
                }) { Text("Copiar", color = Yellow) }
            },
            dismissButton = { TextButton(onClick = { diagOpen = false }) { Text("Fechar", color = Color.White) } },
        )
    }
}

fun formatK(v: Long): String = when {
    v >= 1_000_000_000 -> "%.1fB".format(v / 1_000_000_000.0)
    v >= 1_000_000 -> "%.1fM".format(v / 1_000_000.0)
    v >= 10_000 -> "%.0fk".format(v / 1000.0)
    else -> v.toString()
}
