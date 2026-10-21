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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextDecoration
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
    val listState = rememberLazyListState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.pendingImage.value = uri }

    LaunchedEffect(data.chat.size) { if (data.chat.isNotEmpty()) listState.animateScrollToItem(data.chat.size - 1) }

    Column(modifier.fillMaxSize()) {
        if (settings.activeKey.isBlank()) {
            Text("⚙️ Configure sua chave de IA em Ajustes (ícone de engrenagem). Claude é o padrão; Gemini tem plano gratuito.",
                color = Warn, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            if (data.chat.isEmpty()) item {
                Text("Pergunte qualquer coisa sobre sua conta, mande um print do jogo ou peça o meta atual (🌐 busca na web).",
                    color = Muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            }
            items(data.chat) { m ->
                Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
                    Column(
                        Modifier.widthIn(max = 340.dp)
                            .background(if (m.fromUser) F1Red.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                            .padding(10.dp)
                    ) {
                        SelectionContainer { Text(m.text, fontSize = 14.sp) }
                        m.sources.take(6).forEach { s ->
                            val (title, url) = s.split("|", limit = 2).let { it[0] to it.getOrElse(1) { it[0] } }
                            Text("🔗 $title", fontSize = 12.sp, color = Color(0xFF6CB4FF), textDecoration = TextDecoration.Underline,
                                modifier = Modifier.clickable { onOpenUrl(url) }.padding(top = 2.dp))
                        }
                    }
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { vm.ask(CoachPrompts.WEEKLY_META, webSearch = true) }, label = { Text("🌐 Meta da semana") })
            AssistChip(onClick = { vm.ask(CoachPrompts.EVOLUTION_PLAN) }, label = { Text("📈 Plano de evolução") })
            AssistChip(onClick = { vm.ask("Quais eventos estão ativos no F1 Clash agora e quais valem a pena para a minha conta? Quanto devo gastar em cada um?", webSearch = true) }, label = { Text("🎯 Eventos ativos") })
            AssistChip(onClick = { vm.ask("Analise minha garagem: qual é meu maior gargalo para subir de série e o que fazer nas próximas 2 semanas?") }, label = { Text("🔍 Gargalo da conta") })
            AssistChip(onClick = { vm.ask("Como gastar meus bucks de forma mais eficiente agora? Considere a minha série e garagem.", webSearch = true) }, label = { Text("💰 Bucks") })
        }
        if (image != null) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("📎 Print anexado", fontSize = 13.sp, modifier = Modifier.weight(1f))
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
            OutlinedTextField(input, { input = it }, modifier = Modifier.weight(1f), placeholder = { Text(if (web) "Pergunte (com web)…" else "Pergunte ao Coach…") }, maxLines = 4)
            IconButton(enabled = !busy && (input.isNotBlank() || image != null), onClick = {
                vm.ask(input, image, web)
                input = ""; vm.pendingImage.value = null
            }) { Icon(Icons.AutoMirrored.Filled.Send, "Enviar") }
            IconButton(onClick = { vm.store.clearChat() }) { Icon(Icons.Filled.DeleteSweep, "Limpar conversa", tint = Muted) }
        }
    }
}
