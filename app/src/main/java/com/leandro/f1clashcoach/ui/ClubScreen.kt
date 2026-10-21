package com.leandro.f1clashcoach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leandro.f1clashcoach.core.ai.CoachPrompts
import com.leandro.f1clashcoach.core.engine.ClubManager
import com.leandro.f1clashcoach.core.engine.DailyBriefing

/** Painel do presidente: membros, regra do clube, quem excluir e quando. */
@Composable
fun ClubScreen(vm: MainViewModel, modifier: Modifier, goAi: () -> Unit) {
    val data by vm.store.data.collectAsState()
    val now = remember(data) { System.currentTimeMillis() }
    val club = data.club
    val report = remember(club, data.garage.playerName) { ClubManager.evaluate(club, data.garage.playerName, now) }
    var rulesText by remember(club.rules.text) { mutableStateOf(club.rules.text) }
    var minRep by remember(club.rules.minRepWeek) { mutableStateOf(club.rules.minRepWeek?.toString() ?: "") }
    var days by remember(club.rules.maxInactiveDays) { mutableStateOf(club.rules.maxInactiveDays?.toString() ?: "") }
    val maxRep = club.members.maxOfOrNull { it.rep }?.coerceAtLeast(1) ?: 1

    LazyColumn(modifier) {
        item {
            HeroCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(data.garage.clubName.ifBlank { "Seu clube" }, fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("Presidente", Gold)
                        club.leagueName?.let { Pill(it, Sky) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BigNumber("${club.memberCount ?: club.members.size}/50", "membros", Color.White, Modifier.weight(1f))
                        BigNumber(club.rep?.let { "★" + formatK(it) } ?: "—", "reputação", Gold, Modifier.weight(1f))
                        BigNumber(club.leagueEndsAt?.let { DailyBriefing.formatLeft(it - now) } ?: "—", "fim da liga", Sky, Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            SectionCard("🚪 Quem excluir") {
                Text("⏰ ${report.whenToAct}", fontSize = 13.sp, color = if (report.kicks.any { it.urgency == 2 }) F1Red else Warn)
                if (report.kicks.isEmpty()) Text("✅ Todos dentro da regra até agora.", fontSize = 13.sp, color = Good)
                report.kicks.forEach { k ->
                    ActionRow(
                        when (k.urgency) { 2 -> "🔴"; 1 -> "🟡"; else -> "💬" },
                        "${k.name} • ${k.role}",
                        k.reason + when (k.urgency) { 2 -> "\nExcluir agora"; 1 -> "\nExcluir no fim da liga se não reagir"; else -> "\nConverse antes (cargo de confiança ou dado incompleto)" },
                        when (k.urgency) { 2 -> F1Red; 1 -> Warn; else -> Sky },
                    )
                }
                report.summary.forEach { Text("• $it", fontSize = 12.sp, color = Muted) }
            }
        }
        item {
            SectionCard("📜 Regra do clube") {
                Text("Leio a regra da descrição do clube no jogo. Se não estiver lá, escreva aqui (ex.: \"mínimo 300 de reputação por semana; 3 dias sem correr = expulsão\").",
                    fontSize = 12.sp, color = Muted)
                OutlinedTextField(rulesText, { rulesText = it }, label = { Text("Regra") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Rep. mínima/semana", minRep, { minRep = it }, Modifier.weight(1f))
                    NumField("Dias inativo", days, { days = it }, Modifier.weight(1f))
                }
                Button(onClick = { vm.store.setClubRules(rulesText, minRep.toIntOrNull(), days.toIntOrNull()); vm.toast("Regra salva") },
                    colors = ButtonDefaults.buttonColors(containerColor = F1Red), modifier = Modifier.fillMaxWidth()) { Text("Salvar regra", fontWeight = FontWeight.Bold) }
            }
        }
        item {
            SectionCard("🤖 Plano para subir na liga") {
                Text("A IA usa os membros, a regra e a liga para montar metas por pessoa e a mensagem para o bate-papo.", fontSize = 12.sp, color = Muted)
                Button(onClick = { vm.ask(CoachPrompts.CLUB_PLAN, webSearch = true); goAi() }, modifier = Modifier.fillMaxWidth()) { Text("⭐ Montar plano do clube") }
            }
        }
        item {
            SectionCard("👥 Membros (${club.members.size})") {
                if (club.members.isEmpty()) Text("Abra Clube › Membros no jogo com a leitura ligada e role a lista até o fim. Repita todo dia para eu medir quem corre.", fontSize = 12.sp, color = Muted)
            }
        }
        items(club.members) { m ->
            val rate = report.weeklyRate[m.key]
            val idleDays = ((now - m.lastGrowth) / 86_400_000L).toInt()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (m.role != "Membro") Pill(m.role, Gold, Modifier.padding(end = 6.dp))
                    Text("★${m.rep}", fontSize = 13.sp, color = Gold)
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    ProgressLine(m.rep.toFloat() / maxRep, if (idleDays >= (club.rules.maxInactiveDays ?: 3)) F1Red else Good, height = 6.dp)
                }
                Text(listOfNotNull(rate?.let { "${it.toInt()} rep/semana" }, if (idleDays >= 1) "sem subir há $idleDays d" else "ativo hoje").joinToString(" • "),
                    fontSize = 11.sp, color = Muted)
            }
        }
        item { Box(Modifier.width(1.dp).padding(16.dp).clip(RoundedCornerShape(1.dp)).background(Color.Transparent)) }
    }
}
