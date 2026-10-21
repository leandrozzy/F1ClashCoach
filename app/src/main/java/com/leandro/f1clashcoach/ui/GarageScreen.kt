package com.leandro.f1clashcoach.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leandro.f1clashcoach.core.model.CarStats
import com.leandro.f1clashcoach.core.model.Driver
import com.leandro.f1clashcoach.core.model.DriverStats
import com.leandro.f1clashcoach.core.model.Garage
import com.leandro.f1clashcoach.core.model.Part
import com.leandro.f1clashcoach.core.model.PartSlot
import com.leandro.f1clashcoach.core.model.Progress
import com.leandro.f1clashcoach.core.model.Rarity
import java.util.UUID

@Composable
fun GarageScreen(vm: MainViewModel, modifier: Modifier) {
    val data by vm.store.data.collectAsState()
    val g = data.garage
    var tab by remember { mutableIntStateOf(0) }
    var editPart by remember { mutableStateOf<Part?>(null) }
    var editDriver by remember { mutableStateOf<Driver?>(null) }
    var editAccount by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris -> vm.scanScreenshots(uris) }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().clickable { editAccount = true }.padding(12.dp), horizontalArrangement = Arrangement.SpaceAround) {
            Stat("Série", g.series.toString()); Stat("Troféus", g.trophies.toString())
            Stat("Moedas", formatK(g.coins)); Stat("Bucks", formatK(g.bucks))
        }
        Text("Toque nos números para editar", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(horizontal = 12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) {
                Text("📷 Ler prints", fontSize = 13.sp)
            }
            OutlinedButton(onClick = {
                if (tab == 0) editPart = Part(UUID.randomUUID().toString(), PartSlot.ENGINE, "") else editDriver = Driver(UUID.randomUUID().toString(), "")
            }, modifier = Modifier.weight(1f)) { Text(if (tab == 0) "+ Peça" else "+ Piloto", fontSize = 13.sp) }
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Peças (${g.parts.size})") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Pilotos (${g.drivers.size})") })
        }
        LazyColumn {
            if (tab == 0) {
                if (g.parts.isEmpty()) item { EmptyHint() }
                PartSlot.entries.forEach { slot ->
                    val list = g.parts.filter { it.slot == slot }.sortedByDescending { it.stats.total }
                    if (list.isNotEmpty()) {
                        item { Text(slot.label, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp, top = 10.dp)) }
                        items(list, key = { it.id }) { p -> PartRow(p) { editPart = p } }
                    }
                }
            } else {
                if (g.drivers.isEmpty()) item { EmptyHint() }
                items(g.drivers.sortedByDescending { it.stats.total }, key = { it.id }) { d -> DriverRow(d) { editDriver = d } }
            }
            item { Text(" ", Modifier.padding(20.dp)) }
        }
    }

    editPart?.let { p -> PartDialog(p, onDismiss = { editPart = null }, onSave = { vm.store.upsertPart(it); editPart = null }, onDelete = { vm.store.removePart(p.id); editPart = null }) }
    editDriver?.let { d -> DriverDialog(d, onDismiss = { editDriver = null }, onSave = { vm.store.upsertDriver(it); editDriver = null }, onDelete = { vm.store.removeDriver(d.id); editDriver = null }) }
    if (editAccount) AccountDialog(g, onDismiss = { editAccount = false }) { ng -> vm.store.update { it.copy(garage = ng) }; editAccount = false }
}

@Composable
private fun EmptyHint() {
    Text(
        "Vazio. Jeito mais rápido: ligue o Coach (Início) e abra cada peça/piloto no jogo — eu leio a tela e preencho. " +
            "Ou tire prints dessas telas e toque em \"Ler prints\".",
        fontSize = 13.sp, color = Muted, modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun PartRow(p: Part, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((if (p.equipped) "✅ " else "") + p.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("Nv ${p.progress.level} • ${p.rarity.label}", fontSize = 12.sp, color = Muted)
        }
        val s = p.stats
        Text("VEL ${s.speed}  CUR ${s.cornering}  PU ${s.powerUnit}  QUA ${s.qualifying}  PIT ${s.pitStop}s  OVT ${s.overtakeMode}", fontSize = 12.sp)
        CardsBar(p.progress)
    }
}

@Composable
private fun DriverRow(d: Driver, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((if (d.equipped) "✅ " else "") + d.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("Nv ${d.progress.level} • ${d.rarity.label}", fontSize = 12.sp, color = Muted)
        }
        val s = d.stats
        Text("ULT ${s.overtaking}  DEF ${s.defending}  QUA ${s.qualifying}  LAR ${s.raceStart}  PNEU ${s.tyreManagement}", fontSize = 12.sp)
        CardsBar(d.progress)
    }
}

@Composable
private fun CardsBar(pr: Progress) {
    if (pr.cardsNeeded <= 0) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(progress = { (pr.cardsOwned.toFloat() / pr.cardsNeeded).coerceIn(0f, 1f) }, modifier = Modifier.weight(1f),
            color = if (pr.canUpgrade) Good else F1Red)
        Text("  ${pr.cardsOwned}/${pr.cardsNeeded}" + if (pr.upgradeCoins > 0) " • ${formatK(pr.upgradeCoins.toLong())}🪙" else "", fontSize = 11.sp)
    }
}

@Composable
private fun PartDialog(p: Part, onDismiss: () -> Unit, onSave: (Part) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(p.name) }
    var slot by remember { mutableStateOf(p.slot) }
    var rarity by remember { mutableStateOf(p.rarity) }
    var equipped by remember { mutableStateOf(p.equipped) }
    val f = remember {
        mutableMapOf(
            "lvl" to mutableStateOf(p.progress.level.toString()), "own" to mutableStateOf(p.progress.cardsOwned.toString()),
            "need" to mutableStateOf(p.progress.cardsNeeded.toString()), "cost" to mutableStateOf(p.progress.upgradeCoins.toString()),
            "spd" to mutableStateOf(p.stats.speed.toString()), "cor" to mutableStateOf(p.stats.cornering.toString()),
            "pu" to mutableStateOf(p.stats.powerUnit.toString()), "qua" to mutableStateOf(p.stats.qualifying.toString()),
            "pit" to mutableStateOf(p.stats.pitStop.toString()), "ovt" to mutableStateOf(p.stats.overtakeMode.toString()),
            "nspd" to mutableStateOf(p.nextLevelStats?.speed?.toString() ?: ""), "ncor" to mutableStateOf(p.nextLevelStats?.cornering?.toString() ?: ""),
            "npu" to mutableStateOf(p.nextLevelStats?.powerUnit?.toString() ?: ""), "nqua" to mutableStateOf(p.nextLevelStats?.qualifying?.toString() ?: ""),
        )
    }
    fun v(k: String) = f.getValue(k).value
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (p.name.isBlank()) "Nova peça" else "Editar peça") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Picker("Slot", PartSlot.entries, slot, { it.label }, { slot = it }, Modifier.fillMaxWidth())
                Picker("Raridade", Rarity.entries, rarity, { it.label }, { rarity = it }, Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(equipped, { equipped = it }); Text("Equipada") }
                Grid(f, listOf("lvl" to "Nível", "own" to "Cartas", "need" to "Cartas p/ subir", "cost" to "Custo (moedas)"))
                Text("Atributos atuais", fontWeight = FontWeight.Bold)
                Grid(f, listOf("spd" to "Speed", "cor" to "Cornering", "pu" to "Power Unit", "qua" to "Qualifying", "pit" to "Pit stop (s)", "ovt" to "Overtake"), decimalKeys = setOf("pit"))
                Text("Próximo nível (opcional, melhora o plano)", fontWeight = FontWeight.Bold)
                Grid(f, listOf("nspd" to "Speed", "ncor" to "Cornering", "npu" to "Power Unit", "nqua" to "Qualifying"))
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val next = if (listOf("nspd", "ncor", "npu", "nqua").any { v(it).isNotBlank() })
                    CarStats(v("nspd").toIntOr0(), v("ncor").toIntOr0(), v("npu").toIntOr0(), v("nqua").toIntOr0(), v("pit").toDoubleOr0(), v("ovt").toIntOr0()) else null
                onSave(p.copy(
                    name = name.trim(), slot = slot, rarity = rarity, equipped = equipped,
                    progress = Progress(v("lvl").toIntOr0().coerceAtLeast(1), v("own").toIntOr0(), v("need").toIntOr0(), v("cost").toIntOr0()),
                    stats = CarStats(v("spd").toIntOr0(), v("cor").toIntOr0(), v("pu").toIntOr0(), v("qua").toIntOr0(), v("pit").toDoubleOr0(), v("ovt").toIntOr0()),
                    nextLevelStats = next,
                ))
            }) { Text("Salvar") }
        },
        dismissButton = {
            Row {
                if (p.name.isNotBlank()) TextButton(onClick = onDelete) { Text("Excluir", color = F1Red) }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}

@Composable
private fun DriverDialog(d: Driver, onDismiss: () -> Unit, onSave: (Driver) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(d.name) }
    var rarity by remember { mutableStateOf(d.rarity) }
    var equipped by remember { mutableStateOf(d.equipped) }
    val f = remember {
        mutableMapOf(
            "lvl" to mutableStateOf(d.progress.level.toString()), "own" to mutableStateOf(d.progress.cardsOwned.toString()),
            "need" to mutableStateOf(d.progress.cardsNeeded.toString()), "cost" to mutableStateOf(d.progress.upgradeCoins.toString()),
            "ovt" to mutableStateOf(d.stats.overtaking.toString()), "def" to mutableStateOf(d.stats.defending.toString()),
            "qua" to mutableStateOf(d.stats.qualifying.toString()), "lar" to mutableStateOf(d.stats.raceStart.toString()),
            "tm" to mutableStateOf(d.stats.tyreManagement.toString()),
        )
    }
    fun v(k: String) = f.getValue(k).value
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (d.name.isBlank()) "Novo piloto" else "Editar piloto") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Picker("Raridade", Rarity.entries, rarity, { it.label }, { rarity = it }, Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(equipped, { equipped = it }); Text("Escalado") }
                Grid(f, listOf("lvl" to "Nível", "own" to "Cartas", "need" to "Cartas p/ subir", "cost" to "Custo (moedas)"))
                Text("Atributos", fontWeight = FontWeight.Bold)
                Grid(f, listOf("ovt" to "Overtaking", "def" to "Defending", "qua" to "Qualifying", "lar" to "Race Start", "tm" to "Tyre Mgmt"))
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(d.copy(
                    name = name.trim(), rarity = rarity, equipped = equipped,
                    progress = Progress(v("lvl").toIntOr0().coerceAtLeast(1), v("own").toIntOr0(), v("need").toIntOr0(), v("cost").toIntOr0()),
                    stats = DriverStats(v("ovt").toIntOr0(), v("def").toIntOr0(), v("qua").toIntOr0(), v("lar").toIntOr0(), v("tm").toIntOr0()),
                ))
            }) { Text("Salvar") }
        },
        dismissButton = {
            Row {
                if (d.name.isNotBlank()) TextButton(onClick = onDelete) { Text("Excluir", color = F1Red) }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}

@Composable
private fun AccountDialog(g: Garage, onDismiss: () -> Unit, onSave: (Garage) -> Unit) {
    var series by remember { mutableStateOf(g.series.toString()) }
    var trophies by remember { mutableStateOf(g.trophies.toString()) }
    var coins by remember { mutableStateOf(g.coins.toString()) }
    var bucks by remember { mutableStateOf(g.bucks.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Conta") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NumField("Série", series, { series = it }, Modifier.fillMaxWidth())
                NumField("Troféus", trophies, { trophies = it }, Modifier.fillMaxWidth())
                NumField("Moedas", coins, { coins = it }, Modifier.fillMaxWidth())
                NumField("Bucks", bucks, { bucks = it }, Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(g.copy(series = series.toIntOr0().coerceAtLeast(1), trophies = trophies.toIntOr0(), coins = coins.toLongOrNull() ?: 0, bucks = bucks.toLongOrNull() ?: 0))
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun Grid(fields: Map<String, androidx.compose.runtime.MutableState<String>>, keys: List<Pair<String, String>>, decimalKeys: Set<String> = emptySet()) {
    keys.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { (k, label) ->
                val st = fields.getValue(k)
                NumField(label, st.value, { st.value = it }, Modifier.weight(1f), decimal = k in decimalKeys)
            }
            if (row.size == 1) Column(Modifier.weight(1f)) {}
        }
    }
}
