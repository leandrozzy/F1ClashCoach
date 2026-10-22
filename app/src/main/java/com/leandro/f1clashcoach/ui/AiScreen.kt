package com.leandro.f1clashcoach.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leandro.f1clashcoach.core.ai.CoachPrompts

@Composable
fun AiScreen(vm: MainViewModel, modifier: Modifier, onOpenUrl: (String) -> Unit) {
    val data by vm.store.data.collectAsState()
    val busy by vm.aiBusy.collectAsState()
    val settings by vm.settings.state.collectAsState()
    val image by vm.pendingImage.collectAsState()
    var input by remember { mutableStateOf("") }
    var web by remember { mutableStateOf(false) }
    var briefOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.pendingImage.value = uri }

    LaunchedEffect(data.chat.size) { if (data.chat.isNotEmpty()) listState.animateScrollToItem(data.chat.size - 1 + if (data.aiBriefing.isNotBlank()) 1 else 0) }

    Column(modifier.fillMaxSize()) {
        if (!com.leandro.f1clashcoach.ai.AiClient.hasAnyKey(androidx.compose.ui.platform.LocalContext.current)) {
            Text("⚙️ Conecte uma IA grátis em Ajustes (engrenagem): Gemini ou Groq. Se uma falhar, uso a próxima.",
                color = Warn, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            // análise automática do dia (a IA faz sozinha; aqui só se lê)
            if (data.aiBriefing.isNotBlank()) item {
                Column(Modifier.fillMaxWidth().padding(10.dp).background(Navy, RoundedCornerShape(16.dp)).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🤖 Análise do dia", fontSize = 16.sp, fontWeight = FontWeight.Black, color = Color.White, modifier = Modifier.weight(1f))
                        Text("atualizar", fontSize = 12.sp, color = Yellow, modifier = Modifier.clickable { vm.autoBriefing(force = true) }.padding(4.dp))
                    }
                    SelectionContainer { MdText(data.aiBriefing, maxBlocks = if (briefOpen) Int.MAX_VALUE else 8) }
                    Text(if (briefOpen) "ver menos" else "ler tudo", fontSize = 12.sp, color = Yellow, modifier = Modifier.clickable { briefOpen = !briefOpen }.padding(top = 6.dp))
                }
            }
            if (data.chat.isEmpty()) item {
                Text("Pergunte qualquer coisa sobre sua conta, mande um print do jogo ou toque num atalho abaixo.",
                    color = InkMuted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            }
            items(data.chat) { m ->
                Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
                    if (m.fromUser) {
                        // pergunta: discreta, no máximo 4 linhas (os atalhos mandam textos longos)
                        Text(m.text, fontSize = 13.sp, color = Ink, maxLines = 4, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 300.dp).background(Yellow, RoundedCornerShape(14.dp)).padding(10.dp))
                    } else Column(
                        Modifier.fillMaxWidth().background(Navy, RoundedCornerShape(14.dp)).padding(12.dp)
                    ) {
                        SelectionContainer { MdText(m.text) }
                        m.sources.take(6).forEach { s ->
                            val parts = s.split("|", limit = 2)
                            val title = parts[0]
                            val url = parts.getOrElse(1) { parts[0] }
                            Text("🔗 $title", fontSize = 12.sp, color = Color(0xFF6CB4FF), textDecoration = TextDecoration.Underline,
                                modifier = Modifier.clickable { onOpenUrl(url) }.padding(top = 2.dp))
                        }
                    }
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val chipColors = AssistChipDefaults.assistChipColors(containerColor = Navy, labelColor = Color.White)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(colors = chipColors, onClick = { vm.ask(CoachPrompts.WEEKLY_META, webSearch = true) }, label = { Text("🌐 Meta da semana") })
            AssistChip(colors = chipColors, onClick = { vm.ask(CoachPrompts.EVOLUTION_PLAN) }, label = { Text("📈 Plano de evolução") })
            AssistChip(colors = chipColors, onClick = { vm.ask("Quais eventos estão ativos no F1 Clash agora e quais valem a pena para a minha conta? Quanto devo gastar em cada um?", webSearch = true) }, label = { Text("🎯 Eventos ativos") })
            AssistChip(colors = chipColors, onClick = { vm.ask("Analise minha garagem: qual é meu maior gargalo para subir de série e o que fazer nas próximas 2 semanas?") }, label = { Text("🔍 Gargalo da conta") })
            AssistChip(colors = chipColors, onClick = { vm.ask("Como gastar meus bucks de forma mais eficiente agora? Considere a minha série e garagem.", webSearch = true) }, label = { Text("💰 Bucks") })
        }
        if (image != null) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("📎 Print anexado", fontSize = 13.sp, color = Ink, modifier = Modifier.weight(1f))
                Text("remover", color = F1Red, fontSize = 13.sp, modifier = Modifier.clickable { vm.pendingImage.value = null })
            }
        }
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Filled.AddPhotoAlternate, "Anexar print")
            }
            IconButton(onClick = { web = !web }) {
                Icon(Icons.Filled.Language, "Buscar na web", tint = if (web) MaterialTheme.colorScheme.secondary else Muted)
            }
            OutlinedTextField(input, { input = it }, modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Ink, unfocusedTextColor = Ink, focusedPlaceholderColor = InkMuted, unfocusedPlaceholderColor = InkMuted, focusedBorderColor = Navy, unfocusedBorderColor = InkMuted), placeholder = { Text(if (web) "Pergunte (com web)…" else "Pergunte ao Coach…") }, maxLines = 4)
            IconButton(enabled = !busy && (input.isNotBlank() || image != null), onClick = {
                vm.ask(input, image, web)
                input = ""; vm.pendingImage.value = null
            }) { Icon(Icons.AutoMirrored.Filled.Send, "Enviar") }
            IconButton(onClick = { vm.store.clearChat() }) { Icon(Icons.Filled.DeleteSweep, "Limpar conversa", tint = Muted) }
        }
    }
}
