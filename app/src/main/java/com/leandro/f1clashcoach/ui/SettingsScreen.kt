package com.leandro.f1clashcoach.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
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
import com.leandro.f1clashcoach.ai.AiClient
import com.leandro.f1clashcoach.ai.AiKeys
import com.leandro.f1clashcoach.ai.AiStatus
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier) {
    val s by vm.settings.state.collectAsState()
    val ctx = LocalContext.current
    var importOpen by remember { mutableStateOf(false) }

    LazyColumn(modifier) {
        item { AiKeysCard(vm) }
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


private data class KeySpec(val id: String, val pref: String, val name: String, val hint: String, val url: String, val free: Boolean)

private val KEY_SPECS = listOf(
    KeySpec("gemini", AiKeys.GEMINI_KEY, "Gemini (Google)", "Grátis. Pode colar várias chaves separadas por vírgula (cada uma tem cota própria).", "https://aistudio.google.com/apikey", true),
    KeySpec("compat", AiKeys.GROQ_KEY, "Groq", "Grátis e muito rápido. Também pesquisa na web.", "https://console.groq.com/keys", true),
    KeySpec("openrouter", AiKeys.OPENROUTER_KEY, "OpenRouter", "Grátis (modelos :free).", "https://openrouter.ai/keys", true),
    KeySpec("mistral", AiKeys.MISTRAL_KEY, "Mistral", "Plano gratuito com limite.", "https://console.mistral.ai/api-keys", true),
    KeySpec("claude", AiKeys.CLAUDE_KEY, "Claude (Anthropic)", "Pago. O mais inteligente; pesquisa na web.", "https://console.anthropic.com/settings/keys", false),
)

/** Chaves das IAs (mesmas do OSM Coach): o app tenta uma após a outra até alguma responder. */
@Composable
private fun AiKeysCard(vm: MainViewModel) {
    val ctx = LocalContext.current
    val testing by vm.aiTesting.collectAsState()
    val result by vm.aiTest.collectAsState()
    var version by remember { mutableStateOf(0) }
    var stage by remember { mutableStateOf("") }
    LaunchedEffect(testing) { while (testing) { stage = AiStatus.stage; delay(400) } ; stage = "" }
    val have = remember(version) { AiClient.ALL.filter { AiClient.hasKey(ctx, it) } }

    SectionCard("🤖 Inteligência artificial") {
        Text(if (have.isEmpty()) "Nenhuma IA conectada. Pegue uma chave grátis do Gemini ou do Groq (1 minuto) e cole abaixo."
            else "Conectadas: " + have.joinToString { AiClient.label(it) } + ". Se uma falhar, eu uso a próxima sozinho.",
            fontSize = 13.sp, color = if (have.isEmpty()) Warn else Good)
        KEY_SPECS.forEach { k ->
            var value by remember { mutableStateOf(AiKeys.get(ctx, k.pref)) }
            var show by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(k.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (k.free) Pill("grátis", Good) else Pill("pago", Muted)
                    if (AiClient.hasKey(ctx, k.id)) Pill("✓ conectada", Good, Modifier.padding(start = 4.dp))
                }
                Text(k.hint, fontSize = 11.sp, color = Muted)
                OutlinedTextField(value, { v -> value = v; AiKeys.put(ctx, k.pref, v); version++ },
                    label = { Text("Cole a chave aqui") }, singleLine = k.id != "gemini", maxLines = 3,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(k.url))) }) { Text("🔑 Pegar chave") }
                    TextButton(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
                        if (clip.isNotBlank()) {
                            value = if (k.id == "gemini" && value.isNotBlank() && !value.contains(clip)) "$value, $clip" else clip
                            AiKeys.put(ctx, k.pref, value); version++
                        }
                    }) { Text("📋 Colar") }
                    TextButton(onClick = { show = !show }) { Text(if (show) "Ocultar" else "Mostrar") }
                }
            }
        }
        Text("Preferida", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        var pref by remember { mutableStateOf(AiKeys.get(ctx, AiKeys.PREFERRED, "auto")) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
            (listOf("auto") + AiClient.ALL).forEach { p ->
                FilterChip(pref == p, { pref = p; AiKeys.put(ctx, AiKeys.PREFERRED, p) }, { Text(if (p == "auto") "Automática" else AiClient.label(p)) })
            }
        }
        Button(onClick = { vm.testAi() }, enabled = !testing && have.isNotEmpty(), modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = F1Red)) {
            if (testing) CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp, color = Color.White)
            Text(if (testing) "Testando…" else "🧪 Testar IAs", fontWeight = FontWeight.Bold)
        }
        if (testing && stage.isNotBlank()) Text(stage, fontSize = 12.sp, color = Muted)
        result?.let { r ->
            r.lines().forEach { line ->
                Text(line, fontSize = 12.sp, color = when { line.startsWith("✔") -> Good; line.startsWith("✘") -> F1Red; line.startsWith("◐") -> Warn; else -> Muted })
            }
        }
        if ("gemini" in have) Text("Gemini hoje: " + remember(version, testing) { AiClient.geminiStatus(ctx) }, fontSize = 11.sp, color = Muted)
        Text("As chaves ficam só neste aparelho.", fontSize = 11.sp, color = Muted)
    }
}
