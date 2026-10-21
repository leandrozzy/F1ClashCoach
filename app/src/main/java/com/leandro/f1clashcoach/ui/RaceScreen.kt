package com.leandro.f1clashcoach.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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

private fun boostColor(s: BoostStat): Color = when (s) {
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
    var trackId by rememberSaveable { mutableStateOf(TrackCatalog.tracks.first().id) }
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

    LazyColumn(modifier) {
        // ---------- Agora (lido do jogo) ----------
        item {
            HeroCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Próxima corrida", fontSize = 12.sp, color = Muted)
                            Text(race.trackName ?: "Abra a corrida no jogo", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color.White)
                        }
                        race.laps?.let { BigNumber(if (race.lap != null) "${race.lap}/$it" else "$it", "voltas", Color.White) }
                    }
                    race.odds?.let { Pill("🆚 $it", Sky) }
                    if (race.ranges.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            race.ranges.entries.sortedBy { it.key.ordinal }.forEach { (t, r) ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    TyreBadge(t, 40.dp)
                                    Text("${r.first}-${r.last} v", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    race.lapTimes[t]?.let { Text("%.2fs".format(it), fontSize = 10.sp, color = Muted) }
                                }
                            }
                        }
                    } else Text("Na tela de escolha de pneus eu leio as voltas, a faixa de cada pneu e o tempo por volta — e monto o plano com os seus reforços.",
                        fontSize = 12.sp, color = Muted)
                    race.plans.firstOrNull()?.let { ActionRow("✅", "Plano: $it", "Pelas faixas e tempos do jogo", Good) }
                    race.pitLines.forEach { ActionRow("🛞", it, "", if (it.contains("BOX")) F1Red else Warn) }
                    race.boosterLines.take(3).forEach { Text("🧪 $it", fontSize = 12.sp, color = Color.White) }
                }
            }
        }

        // ---------- Reforços ----------
        item {
            SectionCard("🧪 Seus reforços (${owned.sumOf { it.count }})") {
                if (owned.isEmpty()) Text("Abra Equipe › Reforços no jogo uma vez: eu leio quantos você tem de cada.", fontSize = 12.sp, color = Warn)
                owned.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { b ->
                            val main = b.def?.effects?.maxByOrNull { it.value }?.key
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(PanelHi).padding(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    BottleIcon(main?.let { boostColor(it) } ?: Muted, 30.dp)
                                    Spacer(Modifier.weight(1f))
                                    Text("${b.count}", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color.White)
                                }
                                Text(b.name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
                                b.def?.effects?.entries?.sortedByDescending { it.value }?.forEach { (s, v) ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(7.dp).clip(CircleShape).background(boostColor(s)))
                                        Text(" ${s.short} +$v", fontSize = 10.sp, color = Muted)
                                    }
                                } ?: Text("bônus não catalogado", fontSize = 10.sp, color = Muted)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Text("Regra do jogo: 1 reforço por piloto na largada e mais 1 a cada pit. \"Largada\" só vale na 1ª volta.", fontSize = 11.sp, color = Muted)
            }
        }

        // ---------- Planejar uma corrida ----------
        item {
            SectionCard("📋 Planejar corrida") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TrackCatalog.tracks.forEach { t -> FilterPill(t.name, t.id == trackId, Sky) { trackId = t.id } }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Voltas", fontSize = 13.sp, color = Muted)
                    FilterPill("−", false) { if (laps > 2) laps-- }
                    Text("$laps", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color.White)
                    FilterPill("+", false) { if (laps < 20) laps++ }
                }
                if (race.ranges.isEmpty()) Text("Usando as faixas padrão (Soft 1-2, Medium 2-3, Hard 3-5). A tela de pneus do jogo atualiza com as reais.", fontSize = 11.sp, color = Warn)
                plans.forEachIndexed { i, p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(if (i == 0) Good.copy(alpha = 0.12f) else PanelHi).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (i == 0) "🥇" else if (i == 1) "🥈" else "🥉", fontSize = 18.sp)
                        Spacer(Modifier.width(8.dp))
                        p.stints.forEachIndexed { si, (t, n) ->
                            if (si > 0) Text(" › ", color = Muted)
                            TyreBadge(t, 26.dp); Text(" ${n}v", fontSize = 12.sp, color = Color.White)
                        }
                        Spacer(Modifier.weight(1f))
                        if (i > 0) Text("+%.1fs".format(p.totalTime - plans[0].totalTime), fontSize = 11.sp, color = Muted)
                    }
                }
                if (boostPlan.lines.isNotEmpty()) {
                    SectionTitle("Reforços para esse plano")
                    boostPlan.lines.forEach { Text("🧪 $it", fontSize = 12.sp, color = Color.White) }
                }
                play?.let { tp ->
                    Text("📈 Seu histórico em ${tp.trackName}: ${tp.wins}/${tp.races} vitórias" + (tp.bestStrategy?.let { " • melhor: $it" } ?: ""), fontSize = 12.sp, color = Good)
                }
            }
        }

        // ---------- Setup ----------
        item {
            SectionCard("🔧 Setup para ${track.name}") {
                if (data.garage.parts.isEmpty()) Text("Abra Equipe › Carros no jogo para eu montar o setup.", color = Warn, fontSize = 13.sp)
                setup.choices.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(PanelHi), contentAlignment = Alignment.Center) { PartIcon(c.slot, Color.White, 18.dp) }
                        Spacer(Modifier.width(8.dp))
                        Text(c.slot.label, fontSize = 12.sp, color = Muted, modifier = Modifier.width(90.dp))
                        Text(c.best?.name ?: "—", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
                if (setup.changes.isNotEmpty()) setup.changes.forEach { Text("↔ $it", fontSize = 12.sp, color = Good) }
                else if (data.garage.parts.isNotEmpty()) Text("Seu setup equipado já é o melhor para esta pista ✅", fontSize = 12.sp, color = Good)
            }
        }
        item {
            Button(
                onClick = {
                    vm.ask(CoachPrompts.racePlan(track, laps, "Plano pelas faixas: ${plans.firstOrNull()?.summary() ?: "-"}. Reforços: ${boostPlan.lines.joinToString(" | ")}"), webSearch = true, focusTrack = track)
                    goAi()
                },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = F1Red),
            ) { Text("🤖 Revisar com a IA") }
        }
        item { Spacer(Modifier.height(70.dp)) }
    }
}
