package com.leandro.f1clashcoach.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun NewsScreen(vm: MainViewModel, modifier: Modifier, onOpenUrl: (String) -> Unit, goAi: () -> Unit) {
    val news by vm.news.collectAsState()
    val errors by vm.newsErrors.collectAsState()
    val loading by vm.newsLoading.collectAsState()
    LaunchedEffect(Unit) { if (news.isEmpty()) vm.loadNews() }

    LazyColumn(modifier) {
        item {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = {
                vm.ask("Pesquise as novidades mais recentes do F1 Clash (patch notes, eventos, temporada, mudanças de balanceamento) e me diga o que muda para a minha conta.", webSearch = true)
                goAi()
            }, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("🤖 Resumir novidades com IA") }
        }
        items(news) { n ->
            Column(Modifier.fillMaxWidth().clickable { onOpenUrl(n.url) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(n.title, fontSize = 14.sp)
                Text(n.source, fontSize = 11.sp, color = InkMuted)
            }
        }
        if (!loading && news.isEmpty()) item { Text("Nada carregado. Verifique a internet ou use o resumo com IA.", color = InkMuted, modifier = Modifier.padding(16.dp)) }
        if (errors.isNotEmpty()) item { Text("Fontes indisponíveis: " + errors.joinToString(), fontSize = 11.sp, color = InkMuted, modifier = Modifier.padding(16.dp)) }
        item { OutlinedButton(onClick = { vm.loadNews() }, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("Recarregar") } }
    }
}
