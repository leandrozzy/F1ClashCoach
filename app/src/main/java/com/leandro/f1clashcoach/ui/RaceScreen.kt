package com.leandro.f1clashcoach.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leandro.f1clashcoach.capture.LiveState
import com.leandro.f1clashcoach.core.ai.CoachPrompts
import com.leandro.f1clashcoach.core.engine.BoostStat
import com.leandro.f1clashcoach.core.engine.Boosters
import com.leandro.f1clashcoach.core.engine.RangePlanner
import com.leandro.f1clashcoach.core.engine.SetupOptimizer
import com.leandro.f1clashcoach.core.engine.TrackPlaybook
import com.leandro.f1clashcoach.core.model.TrackCatalog
import com.leandro.f1clashcoach.core.model.Tyre

/** Cores dos compostos (padrão F1). */
fun tyreColor(t: Tyre): Color = when (t) {
    Tyre.SOFT -> Color(0xFFE10600)
    Tyre.MEDIUM -> Color(0xFFF5C542)
    Tyre.HARD -> Color(0xFFEDEDED)
    Tyre.INTER -> Color(0xFF2ECC71)
    Tyre.WET -> Color(0xFF3FA9F5)
}

/** Pneu desenhado: anel na cor do composto com a letra. */
@Composable
fun TyreBadge(t: Tyre, size: Dp = 34.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            drawCircle(Color(0xFF1A1A1A))
            drawCircle(tyreColor(t), radius = this.size.minDimension * 0.42f, style = androidx.compose.ui.graphics.drawscope.Stroke(this.size.minDimension * 0.13f))
        }
        Text(t.short, fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.Black, color = Color.White)
    }
}

fun boostColor(s: BoostStat): Color = when (s) {
    BoostStat.OVERTAKING, BoostStat.OVERTAKE_MODE -> Color(0xFFE10600)
    BoostStat.DEFENDING -> Color(0xFF3FA9F5)
    BoostStat.RACE_START -> Color(0xFFF2811D)
    BoostStat.TYRE -> Color(0xFF2ECC71)
    BoostStat.SPEED -> Color(0xFFF5C542)
    BoostStat.CORNERING -> Color(0xFF8B3FE0)
    BoostStat.POWER_UNIT -> Color(0xFFFF6B9A)
    BoostStat.PIT -> Color(0xFF1ABC9C)
}

/** Garrafa de reforço desenhada, na cor do bônus principal. */
@Composable
fun BottleIcon(color: Color, size: Dp = 40.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension / 24f
        drawRoundRect(Color(0xFF2B2F38), Offset(9 * s, 1.5f * s), Size(6 * s, 3 * s), CornerRadius(1 * s, 1 * s))
        val body = Path().apply {
            moveTo(8 * s, 5 * s); lineTo(16 * s, 5 * s)
            cubicTo(18 * s, 6 * s, 18.5f * s, 8 * s, 18 * s, 10 * s)
            lineTo(18 * s, 20 * s); cubicTo(18 * s, 22 * s, 16.5f * s, 23 * s, 15 * s, 23 * s)
            lineTo(9 * s, 23 * s); cubicTo(7.5f * s, 23 * s, 6 * s, 22 * s, 6 * s, 20 * s)
            lineTo(6 * s, 10 * s); cubicTo(5.5f * s, 8 * s, 6 * s, 6 * s, 8 * s, 5 * s); close()
        }
        drawPath(body, color)
        drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(8 * s, 9 * s), Size(2 * s, 11 * s), CornerRadius(1 * s, 1 * s))
    }
}

@Composable
fun RaceScreen(vm: MainViewModel, modifier: Modifier, goAi: () -> Unit) {
    val data by vm.store.data.collectAsState()
    val live by LiveState.status.collectAsState()
    val race = live.race
    // começa na pista da última corrida lida (não no Bahrein fixo)
    var trackId by rememberSaveable { mutableStateOf(data.races.maxByOrNull { it.timestamp }?.trackId?.takeIf { TrackCatalog.byId(it) != null } ?: TrackCatalog.tracks.first().id) }
    var laps by remember { mutableIntStateOf(race.laps ?: data.series[data.garage.series]?.laps ?: 6) }
    val track = TrackCatalog.byId(trackId)!!
    val owned = remember(data.garage.boosters) { Boosters.owned(data.garage.boosters).sortedByDescending { it.count } }
    // faixas: as lidas do jogo; sem leitura, as faixas padrão da temporada (marcado na tela)
    val ranges = race.ranges.ifEmpty { mapOf(Tyre.SOFT to 1..2, Tyre.MEDIUM to 2..3, Tyre.HARD to 3..5) }
    val times = race.lapTimes.ifEmpty { mapOf(Tyre.SOFT to 39.76, Tyre.MEDIUM to 40.31, Tyre.HARD to 40.81) }
    val pitLoss = (data.garage.carTotals?.pitStop?.takeIf { it > 0 } ?: 4.0) + 4.0
    val plans = remember(laps, ranges, times, pitLoss) { RangePlanner.plans(laps, ranges, times, pitLoss) }
    val boostPlan = remember(data.garage.boosters, trackId, plans) { Boosters.plan(data.garage.boosters, track, plans.firstOrNull()?.stops ?: 1, null) }
    val setup = remember(data.garage, trackId) { SetupOptimizer.optimize(data.garage, track) }
    val play = remember(data.races, trackId) { TrackPlaybook.forTrack(data.races, trackId) }

    var allStock by remember { mutableStateOf(false) }
    val best = plans.firstOrNull()

    LazyColumn(modifier) {
        // ---------- Plano da próxima corrida: tudo num cartão só, visual ----------
        item {
            HeroCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (race.trackName != null) "Corrida lida do jogo" else "Planejar corrida", fontSize = 12.sp, color = Muted)
                            Text(race.trackName ?: track.name, fontSize = 22.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = Color.White)
                        }
                        BigNumber(if (race.lap != null && race.laps != null) "${race.lap}/${race.laps}" else "$laps", "voltas", Color.White)
                    }
                    race.odds?.let { Pill("🆚 $it", Sky) }
                    // faixas de cada pneu (as do jogo; sem leitura, as padrão)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ranges.entries.sortedBy { it.key.ordinal }.forEach { (t, r) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                TyreBadge(t, 34.dp)
                                Text("${r.first}-${r.last} v", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                    if (race.ranges.isEmpty()) Text("Faixas padrão. Na tela de pneus do jogo eu leio as reais e o plano se ajusta.", fontSize = 11.sp, color = Muted)
                    // estratégia: barra com um trecho por pneu, do tamanho das voltas
                    best?.let { p ->
                        Text("Estratégia", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Muted)
                        Row(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(10.dp))) {
                            p.stints.forEachIndexed { i, (t, n) ->
                                Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(tyreColor(t)).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                                    Text("${t.short} • ${n}v", fontSize = 13.sp, fontWeight = FontWeight.Black, color = if (t == Tyre.HARD || t == Tyre.MEDIUM) Ink else Color.White)
                                }
                                if (i < p.stints.size - 1) Box(Modifier.width(3.dp).fillMaxHeight().background(Navy))
                            }
                        }
                        val pitAt = p.stints.dropLast(1).runningFold(0) { acc, st -> acc + st.second }.drop(1)
                        Text(if (p.stops == 0) "Sem parada." else "Pit no fim da volta ${pitAt.joinToString(" e ")}.", fontSize = 13.sp, color = Color.White)
                    }
                    // reforços por piloto e trecho
                    if (boostPlan.slots.isNotEmpty()) {
                        Text("Reforços", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Muted)
                        boostPlan.slots.groupBy { it.stint }.toSortedMap().forEach { (stint, sl) ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(if (stint == 0) "Largada" else "Após ${stint}º pit", fontSize = 11.sp, color = Muted, modifier = Modifier.width(70.dp))
                                sl.sortedBy { it.driver }.forEach { b ->
                                    val main = Boosters.find(b.booster)?.effects?.maxByOrNull { it.value }?.key
                                    Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(PanelHi).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        BottleIcon(main?.let { boostColor(it) } ?: Muted, 22.dp)
                                        Spacer(Modifier.width(4.dp))
                                        Column {
                                            Text("P${b.driver + 1}", fontSize = 9.sp, color = Muted)
                                            Text(b.booster, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    } else if (owned.isEmpty()) Text("🧪 Abra Equipe › Reforços no jogo uma vez para eu planejar os reforços.", fontSize = 12.sp, color = Warn)
                    // ao vivo (durante a corrida)
                    race.pitLines.forEach { ActionRow("🛞", it, "", if (it.contains("BOX")) F1Red else Warn) }
                }
            }
        }

        // ---------- Pista e voltas para planejar outra corrida ----------
        item {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TrackCatalog.tracks.forEach { t -> FilterPill(t.name, t.id == trackId, Sky) { trackId = t.id } }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Voltas", fontSize = 13.sp, color = InkMuted)
                    FilterPill("−", false) { if (laps > 2) laps-- }
                    Text("$laps", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Ink)
                    FilterPill("+", false) { if (laps < 20) laps++ }
                    Spacer(Modifier.weight(1f))
                    play?.let { tp -> Text("${tp.wins}/${tp.races} vitórias aqui", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Ink) }
                }
                if (plans.size > 1) plans.drop(1).forEach { p ->
                    Text("Alternativa: ${p.summary()} (+%.1fs)".format(p.totalTime - plans[0].totalTime), fontSize = 12.sp, color = InkMuted)
                }
            }
        }

        // ---------- Setup: só o que trocar ----------
        item {
            SectionCard("🔧 Setup para ${track.name}") {
                when {
                    data.garage.parts.isEmpty() -> Text("Abra Equipe › Carros no jogo para eu montar o setup.", color = Warn, fontSize = 13.sp)
                    setup.changes.isEmpty() -> Text("✅ Seu carro equipado já é o melhor para esta pista.", fontSize = 13.sp, color = Good)
                    else -> setup.changes.forEach { Text("↔ $it", fontSize = 13.sp, color = Color.White) }
                }
            }
        }

        // ---------- Estoque de reforços (recolhido) ----------
        item {
            SectionCard("🧪 Estoque de reforços (${owned.sumOf { it.count }})", action = {
                if (owned.size > 6) Text(if (allStock) "menos" else "ver todos", fontSize = 12.sp, color = Yellow, modifier = Modifier.clickable { allStock = !allStock }.padding(4.dp))
            }) {
                if (owned.isEmpty()) Text("Abra Equipe › Reforços no jogo uma vez: eu leio quantos você tem de cada.", fontSize = 12.sp, color = Warn)
                (if (allStock) owned else owned.take(6)).chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { b ->
                            val main = b.def?.effects?.maxByOrNull { it.value }?.key
                            Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(PanelHi).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                BottleIcon(main?.let { boostColor(it) } ?: Muted, 22.dp)
                                Spacer(Modifier.width(4.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(b.name, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
                                    Text(b.def?.effects?.entries?.sortedByDescending { it.value }?.joinToString(" ") { (st, v) -> "${st.short}+$v" } ?: "", fontSize = 9.sp, color = Muted, maxLines = 1)
                                }
                                Text("${b.count}", fontSize = 15.sp, fontWeight = FontWeight.Black, color = Color.White)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Text("Regra do jogo: 1 reforço por piloto na largada e mais 1 a cada pit. \"Largada\" só vale na 1ª volta.", fontSize = 11.sp, color = Muted)
            }
        }
        item {
            Button(
                onClick = {
                    vm.ask(CoachPrompts.racePlan(track, laps, "Plano pelas faixas: ${best?.summary() ?: "-"}. Reforços: ${boostPlan.lines.joinToString(" | ")}"), webSearch = true, focusTrack = track)
                    goAi()
                },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Navy),
            ) { Text("🤖 Revisar com a IA", color = Color.White) }
        }
        item { Spacer(Modifier.height(70.dp)) }
    }
}
