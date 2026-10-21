package com.leandro.f1clashcoach.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leandro.f1clashcoach.core.ai.CoachPrompts
import com.leandro.f1clashcoach.core.engine.RaceContext
import com.leandro.f1clashcoach.core.engine.RaceStrategist
import com.leandro.f1clashcoach.core.engine.SetupOptimizer
import com.leandro.f1clashcoach.core.engine.StrategyPlan
import com.leandro.f1clashcoach.core.engine.WearModel
import com.leandro.f1clashcoach.core.model.TrackCatalog
import com.leandro.f1clashcoach.core.model.Tyre
import com.leandro.f1clashcoach.core.model.Weather
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun RaceScreen(vm: MainViewModel, modifier: Modifier, goAi: () -> Unit) {
    val data by vm.store.data.collectAsState()
    var trackId by rememberSaveable { mutableStateOf(TrackCatalog.tracks.first().id) }
    var laps by rememberSaveable { mutableStateOf(6) }
    var weather by rememberSaveable { mutableStateOf(Weather.DRY) }
    var rainFrom by remember { mutableFloatStateOf(3f) }
    var startTyre by rememberSaveable { mutableStateOf<Tyre?>(null) }
    var plans by remember { mutableStateOf<List<StrategyPlan>?>(null) }
    var computing by remember { mutableStateOf(false) }

    val track = TrackCatalog.byId(trackId)!!
    val setup = remember(data.garage, trackId) { SetupOptimizer.optimize(data.garage, track) }
    val tm = data.garage.drivers.filter { it.equipped }.map { it.stats.tyreManagement }.ifEmpty { listOf(50) }.average().toInt()
    val pit = data.garage.carTotals?.pitStop?.takeIf { it > 0 } ?: data.garage.parts.filter { it.equipped }.sumOf { it.stats.pitStop }.takeIf { it > 0 } ?: 1.0
    val weatherByLap = if (weather == Weather.DRY) emptyList() else List(laps) { i -> if (i + 1 >= rainFrom.roundToInt()) weather else Weather.DRY }
    val wear = remember(data.tyreObservations) { WearModel(data.tyreObservations) }

    LaunchedEffect(trackId, laps, weather, rainFrom, startTyre, data.tyreObservations.size, tm) {
        computing = true
        plans = withContext(Dispatchers.Default) {
            runCatching {
                RaceStrategist(wear).bestPlans(RaceContext(track, laps, weatherByLap, tm, pit, startTyre = startTyre))
            }.getOrDefault(emptyList())
        }
        computing = false
    }

    LazyColumn(modifier) {
        item {
            SectionCard("Corrida") {
                Picker("Pista", TrackCatalog.tracks, track, { "${it.name} (${it.country})" }, { trackId = it.id }, Modifier.fillMaxWidth())
                Text("Perfil: ${track.type.label} • ultrapassar: ${level(track.overtakeDifficulty)} • chuva: ${level(track.rainRisk)} • desgaste: ${level(track.tyreStress)}", fontSize = 12.sp, color = Muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Voltas: $laps", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { if (laps > 2) laps-- }) { Text("−") }
                    OutlinedButton(onClick = { if (laps < 30) laps++ }, modifier = Modifier.padding(start = 6.dp)) { Text("+") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Weather.entries.forEach { w -> FilterChip(selected = weather == w, onClick = { weather = w }, label = { Text(w.label, fontSize = 12.sp) }) }
                }
                if (weather != Weather.DRY) {
                    Text("Chuva a partir da volta ${rainFrom.roundToInt()}", fontSize = 13.sp)
                    Slider(value = rainFrom, onValueChange = { rainFrom = it }, valueRange = 1f..laps.toFloat(), steps = (laps - 2).coerceAtLeast(0))
                }
                Picker("Pneu de largada", listOf<Tyre?>(null) + Tyre.entries, startTyre, { it?.label ?: "o melhor" }, { startTyre = it }, Modifier.fillMaxWidth())
            }
        }
        item {
            SectionCard("Estratégia recomendada", action = { if (computing) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp) }) {
                val ps = plans
                if (ps.isNullOrEmpty() && !computing) Text("Nenhuma estratégia viável — aumente as voltas ou troque o pneu de largada.", color = Warn)
                ps?.forEachIndexed { i, p ->
                    Text("${if (i == 0) "🥇" else if (i == 1) "🥈" else "🥉"} ${p.stops} parada(s)" + if (i > 0) "  (+${"%.1f".format(p.estimatedTime - ps[0].estimatedTime)}s)" else "", fontWeight = FontWeight.Bold)
                    p.stints.forEachIndexed { si, s ->
                        Text("   ${if (si > 0) "BOX fim da V${s.fromLap - 1} → " else "Largada: "}${s.tyre.label}, ritmo ${s.pace.label} (V${s.fromLap}–${s.toLap})", fontSize = 13.sp)
                    }
                    p.notes.forEach { Text("   ⚠ $it", fontSize = 12.sp, color = Warn) }
                }
                val samples = Tyre.entries.sumOf { wear.samples(track.id, it) }
                Text(if (samples > 0) "Calibrado com $samples observações reais de pneu nesta pista." else "Desgaste estimado. Corra com o Coach ligado e ele aprende o desgaste real desta pista.", fontSize = 11.sp, color = Muted)
                Text("Energia: guarde para as retas quando estiver a menos de 1s de alguém; na última volta gaste tudo.", fontSize = 12.sp)
            }
        }
        item {
            SectionCard("Setup para ${track.name}") {
                if (data.garage.parts.isEmpty()) Text("Cadastre suas peças na Garagem para eu montar o setup.", color = Warn, fontSize = 13.sp)
                setup.choices.forEach { c ->
                    Text("${c.slot.label}: ${c.best?.name ?: "—"}" + (c.runnerUp?.let { "  (alt: ${it.name})" } ?: ""), fontSize = 13.sp)
                }
                if (setup.drivers.isNotEmpty()) Text("Pilotos: ${setup.drivers.joinToString { it.name }}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (setup.changes.isNotEmpty()) {
                    Text("Trocas sugeridas:", fontWeight = FontWeight.Bold, color = Good)
                    setup.changes.forEach { Text("• $it", fontSize = 13.sp) }
                } else if (data.garage.parts.isNotEmpty()) Text("Seu setup equipado já é o melhor para esta pista. ✅", fontSize = 13.sp, color = Good)
            }
        }
        item {
            Button(
                onClick = {
                    val best = plans?.firstOrNull()?.summary() ?: "sem plano"
                    val extra = "Clima: ${weather.label}${if (weather != Weather.DRY) " a partir da volta ${rainFrom.roundToInt()}" else ""}. Estratégia do app: $best."
                    vm.ask(CoachPrompts.racePlan(track, laps, extra), webSearch = true, focusTrack = track)
                    goAi()
                },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = F1Red),
            ) { Text("🌐 Validar com IA + guias da web") }
        }
    }
}

private fun level(v: Double) = when {
    v >= 0.7 -> "alto"
    v >= 0.4 -> "médio"
    else -> "baixo"
}
