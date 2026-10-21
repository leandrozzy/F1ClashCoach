package com.leandro.f1clashcoach.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leandro.f1clashcoach.BuildConfig
import com.leandro.f1clashcoach.core.ai.AiProvider
import com.leandro.f1clashcoach.core.ai.ClaudeAdvisor
import com.leandro.f1clashcoach.core.ai.GeminiAdvisor

@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier) {
    val s by vm.settings.state.collectAsState()
    val ctx = LocalContext.current
    var importOpen by remember { mutableStateOf(false) }

    LazyColumn(modifier) {
        item {
            SectionCard("Inteligência artificial") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiProvider.entries.forEach { p -> FilterChip(s.provider == p, { vm.settings.update { it.copy(provider = p) } }, { Text(p.label) }) }
                }
                if (s.provider == AiProvider.CLAUDE) {
                    OutlinedTextField(s.claudeKey, { v -> vm.settings.update { it.copy(claudeKey = v.trim()) } }, label = { Text("Chave Anthropic (sk-ant-…)") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(s.claudeModel, { v -> vm.settings.update { it.copy(claudeModel = v.trim()) } }, label = { Text("Modelo (vazio = ${ClaudeAdvisor.DEFAULT_MODEL})") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Crie a chave em console.anthropic.com → API Keys. Analisa prints e pesquisa na web.", fontSize = 12.sp, color = Muted)
                } else {
                    OutlinedTextField(s.geminiKey, { v -> vm.settings.update { it.copy(geminiKey = v.trim()) } }, label = { Text("Chave Google AI Studio") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(s.geminiModel, { v -> vm.settings.update { it.copy(geminiModel = v.trim()) } }, label = { Text("Modelo (vazio = ${GeminiAdvisor.DEFAULT_MODEL})") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Crie a chave grátis em aistudio.google.com → Get API key. Tem limite diário.", fontSize = 12.sp, color = Muted)
                }
                Text("A chave fica só neste aparelho.", fontSize = 12.sp, color = Muted)
            }
        }
        item {
            val data by vm.store.data.collectAsState()
            SectionCard("Seu perfil no jogo") {
                OutlinedTextField(data.garage.playerName, { v -> vm.store.update { it.copy(garage = it.garage.copy(playerName = v)) } },
                    label = { Text("Seu nome no F1 Clash") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Usado para achar você nos rankings do Grand Prix e da liga.", fontSize = 12.sp, color = Muted)
            }
        }
        item {
            SectionCard("Coach ao vivo") {
                Text("Leitura da tela a cada ${"%.1f".format(s.ocrIntervalMs / 1000.0)}s (menor = mais rápido, gasta mais bateria)", fontSize = 13.sp)
                Slider(value = s.ocrIntervalMs.toFloat(), onValueChange = { v -> vm.settings.update { it.copy(ocrIntervalMs = (v / 100).toLong() * 100) } }, valueRange = 500f..3000f)
                Text("Transparência do painel", fontSize = 13.sp)
                Slider(value = s.overlayAlpha, onValueChange = { v -> vm.settings.update { it.copy(overlayAlpha = v) } }, valueRange = 0.4f..1f)
                Toggle("Registrar resultados automaticamente", s.autoRecordResults) { v -> vm.settings.update { it.copy(autoRecordResults = v) } }
                Toggle("Preencher garagem ao abrir peças/pilotos", s.autoScanGarage) { v -> vm.settings.update { it.copy(autoScanGarage = v) } }
                Toggle("Aprender desgaste real dos pneus", s.learnTyreWear) { v -> vm.settings.update { it.copy(learnTyreWear = v) } }
            }
        }
        item {
            SectionCard("Backup dos dados") {
                Text("Copie o backup e guarde (ex.: no Google Keep). Para restaurar, cole aqui.", fontSize = 12.sp, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("F1 Clash Coach backup", vm.store.exportJson()))
                        vm.toast("Backup copiado para a área de transferência")
                    }) { Text("Copiar backup") }
                    OutlinedButton(onClick = { importOpen = true }) { Text("Restaurar") }
                }
            }
        }
        item {
            SectionCard("Sobre") {
                Text("F1 Clash Coach ${BuildConfig.VERSION_NAME}", fontSize = 13.sp)
                Text("App pessoal de aconselhamento. Ele só lê a tela e sugere — não joga por você nem toca na tela, para respeitar as regras do jogo.", fontSize = 12.sp, color = Muted)
            }
        }
    }

    if (importOpen) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importOpen = false },
            title = { Text("Restaurar backup") },
            text = { OutlinedTextField(text, { text = it }, label = { Text("Cole o backup aqui") }, maxLines = 8, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { vm.store.importJson(text) }
                        .onSuccess { vm.toast("Dados restaurados"); importOpen = false }
                        .onFailure { vm.toast("Backup inválido: ${it.message}") }
                }) { Text("Restaurar") }
            },
            dismissButton = { TextButton(onClick = { importOpen = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(value, onChange)
    }
}

