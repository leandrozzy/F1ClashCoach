package com.leandro.f1clashcoach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leandro.f1clashcoach.core.ai.CoachPrompts
import com.leandro.f1clashcoach.core.engine.ClubManager
import com.leandro.f1clashcoach.core.engine.DailyBriefing
import com.leandro.f1clashcoach.core.engine.KickAdvice

@Composable
private fun DecisionList(title: String, color: Color, list: List<KickAdvice>, empty: String) {
    var all by remember { mutableStateOf(false) }
    SectionCard("$title (${list.size})", action = {
        if (list.size > 5) Text(if (all) "menos" else "ver todos", fontSize = 12.sp, color = Yellow, modifier = Modifier.clickable { all = !all }.padding(4.dp))
    }) {
        if (list.isEmpty()) Text(empty, fontSize = 12.sp, color = Muted)
        (if (all) list else list.take(5)).forEach { k ->
            // uma linha por pessoa: barra de urgência, nome, cargo e o motivo curto
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PanelHi).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(26.dp).clip(RoundedCornerShape(50)).background(if (k.urgency >= 2) color else if (k.urgency == 1) Yellow else Muted))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(k.name, fontSize = 13.sp, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1)
                    Text(k.reason, fontSize = 10.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (k.role != "Membro") Pill(k.role, Gold)
                Spacer(Modifier.width(6.dp))
                Text(when (k.urgency) { 2 -> "agora"; 1 -> "fim da Exib."; else -> "conferir" }, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = if (k.urgency >= 2) color else if (k.urgency == 1) Yellow else Muted)
            }
        }
    }
}

/** Painel do presidente: regra do clube aplicada (excluir / rebaixar / promover) e membros. */
@Composable
fun ClubScreen(vm: MainViewModel, modifier: Modifier, goAi: () -> Unit) {
    val data by vm.store.data.collectAsState()
    val now = remember(data) { System.currentTimeMillis() }
    val club = data.club
    val report = remember(club, data.garage.playerName) { ClubManager.evaluate(club, data.garage.playerName, now) }
    var rulesText by remember(club.rules.text) { mutableStateOf(club.rules.text) }
    val maxRep = report.members.maxOfOrNull { it.rep }?.coerceAtLeast(1) ?: 1
    val league = ClubManager.leagueKey(club.leagueName)

    LazyColumn(modifier) {
        item {
            Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(18.dp)).background(Navy).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(data.garage.clubName.ifBlank { "Seu clube" }.uppercase(), fontSize = 22.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BigNumber("${club.memberCount ?: report.members.size}/50", "membros", Color.White, Modifier.weight(1f))
                    BigNumber(club.rep?.let { "★" + formatK(it) } ?: "—", "reputação", Gold, Modifier.weight(1f))
                    BigNumber(club.leagueEndsAt?.let { DailyBriefing.formatLeft(it - now) } ?: "—", "fim ${if (league == "atual") "da Exibição" else league}", Sky, Modifier.weight(1f))
                }
                Text("⏰ ${report.whenToAct}", fontSize = 12.sp, color = Yellow)
                report.summary.drop(1).forEach { Text(it, fontSize = 11.sp, color = Muted) }
            }
        }
        item { DecisionList("🚪 Excluir", F1Red, report.kicks, "Ninguém para excluir pela regra.") }
        item { DecisionList("⬇ Rebaixar cargo", Yellow, report.demotions, "Nenhum capitão/vice abaixo do mínimo.") }
        item { DecisionList("⬆ Promover", Mint, report.promotions, "Ninguém atingiu a meta de promoção ainda.") }
        item { PageTitle("Membros", trailing = "${report.members.size} • estrelas em $league") }
        itemsIndexed(report.members, key = { _, m -> m.key }) { i, m ->
            val cur = m.seasons[league] ?: m.rep
            val prev = m.seasons.filterKeys { it != league }.entries.lastOrNull()
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(Panel).padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", fontSize = 15.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = Color.White, modifier = Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                        if (m.role != "Membro") Pill(m.role, if (m.role == "Presidente") F1Red else Sky)
                    }
                    ProgressLine(cur.toFloat() / maxRep, if (cur == 0L) F1Red else Mint, height = 4.dp)
                    prev?.let { Text("${it.key}: ★${it.value}", fontSize = 10.sp, color = Muted) }
                }
                Spacer(Modifier.width(8.dp))
                Text("★$cur", fontSize = 13.sp, fontWeight = FontWeight.Black, color = Gold)
            }
        }
        item {
            SectionCard("📜 Regra do clube") {
                Text("Lida da descrição do clube no jogo. Pode corrigir aqui.", fontSize = 12.sp, color = Muted)
                OutlinedTextField(rulesText, { rulesText = it }, label = { Text("Regra") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                val r = club.rules
                if (r.structured) Text(listOfNotNull(
                    r.kickCurrentMax?.let { "Excluir: ≤ $it ★ na Exibição atual" + (r.kickPrevBelow?.let { p -> " e < $p na anterior" } ?: "") },
                    r.promoteMin?.let { "Promover: ≥ $it ★" + if ((r.promoteSeasons ?: 1) >= 2) " em ${r.promoteSeasons} seguidas" else "" },
                    r.demoteBelow?.let { "Rebaixar cargo: < $it ★" },
                ).joinToString("\n"), fontSize = 12.sp, color = Mint)
                Button(onClick = { vm.store.setClubRules(rulesText, null, null); vm.toast("Regra salva") },
                    colors = ButtonDefaults.buttonColors(containerColor = Yellow), modifier = Modifier.fillMaxWidth()) { Text("Salvar regra", fontWeight = FontWeight.Black, color = Ink) }
            }
        }
        item {
            Button(onClick = { vm.ask(CoachPrompts.CLUB_PLAN, webSearch = true); goAi() }, colors = ButtonDefaults.buttonColors(containerColor = Navy),
                modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("🤖 Mensagem para o bate-papo do clube (IA)", color = Color.White) }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}
