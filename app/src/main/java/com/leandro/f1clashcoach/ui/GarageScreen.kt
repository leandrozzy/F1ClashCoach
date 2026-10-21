package com.leandro.f1clashcoach.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.leandro.f1clashcoach.core.model.Catalog
import com.leandro.f1clashcoach.core.model.CollectionItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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

private enum class Own(val label: String) { ALL("Todos"), MINE("Tenho"), MISSING("Não tenho"), READY("Evoluir já") }

@Composable
fun GarageScreen(vm: MainViewModel, modifier: Modifier) {
    val data by vm.store.data.collectAsState()
    val g = data.garage
    var tab by remember { mutableIntStateOf(0) }
    var own by remember { mutableStateOf(Own.ALL) }
    var slotFilter by remember { mutableStateOf<PartSlot?>(null) }
    var rarityFilter by remember { mutableStateOf<Rarity?>(null) }
    var detail by remember { mutableStateOf<CollectionItem?>(null) }
    var editPart by remember { mutableStateOf<Part?>(null) }
    var editDriver by remember { mutableStateOf<Driver?>(null) }
    var editAccount by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris -> vm.scanScreenshots(uris) }

    val drivers = remember(g) { Catalog.driverCollection(g) }
    val parts = remember(g) { Catalog.partCollection(g) }
    val source = if (tab == 0) drivers else parts
    val shown = source.filter { i ->
        when (own) {
            Own.ALL -> true
            Own.MINE -> i.owned
            Own.MISSING -> !i.owned
            Own.READY -> i.owned && i.cardsNeeded > 0 && i.cardsOwned >= i.cardsNeeded && (i.level ?: 0) < i.maxLevel
        } && (slotFilter == null || i.slot == slotFilter) && (rarityFilter == null || i.rarity == rarityFilter)
    }.sortedWith(compareByDescending<CollectionItem> { it.equipped }.thenByDescending { it.owned }.thenBy { it.rarity.ordinal }.thenByDescending { it.total })

    LazyVerticalGrid(
        columns = GridCells.Fixed(3), modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- Resumo da equipe ----
        item(span = { GridItemSpan(maxLineSpan) }) {
            HeroCard(Modifier.clickable { editAccount = true }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Pontuação da equipe", fontSize = 12.sp, color = Muted)
                            Text(if (g.teamScore > 0) g.teamScore.toString() else "—", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${drivers.count { it.owned }}/${drivers.size} pilotos", fontSize = 12.sp, color = Color.White)
                            Text("${parts.count { it.owned }}/${parts.size} peças", fontSize = 12.sp, color = Color.White)
                            Text("💰 ${formatK(g.coins)}", fontSize = 12.sp, color = Gold)
                        }
                    }
                    g.carTotals?.let { t ->
                        val mx = listOf(t.speed, t.cornering, t.powerUnit, t.qualifying, t.overtakeMode).max().coerceAtLeast(1).toFloat()
                        listOf("Velocidade" to t.speed, "Curvas" to t.cornering, "Potência" to t.powerUnit, "Classificação" to t.qualifying, "Overtake" to t.overtakeMode).forEach { (l, v) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(l, fontSize = 11.sp, color = Muted, modifier = Modifier.width(92.dp))
                                MiniBar(v / mx, Sky, Modifier.weight(1f))
                                Text(" $v", fontSize = 11.sp, color = Color.White, modifier = Modifier.width(36.dp))
                            }
                        }
                        Text("Pit stop médio: ${t.pitStop}s", fontSize = 11.sp, color = Muted)
                    }
                }
            }
        }
        // ---- Abas e filtros ----
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill("Pilotos", tab == 0, icon = { HelmetIcon(Color.White, 16.dp) }) { tab = 0; slotFilter = null }
                    FilterPill("Peças", tab == 1, icon = { PartIcon(PartSlot.ENGINE, Color.White, 16.dp) }) { tab = 1; rarityFilter = null }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Own.entries.forEach { o -> FilterPill(o.label, own == o, Sky) { own = o } }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (tab == 1) {
                        FilterPill("Todas", slotFilter == null, Sky) { slotFilter = null }
                        PartSlot.entries.forEach { sl -> FilterPill(sl.label, slotFilter == sl, Sky, icon = { PartIcon(sl, Color.White, 16.dp) }) { slotFilter = if (slotFilter == sl) null else sl } }
                    } else {
                        listOf(Rarity.COMMON, Rarity.RARE, Rarity.EPIC, Rarity.LEGENDARY).forEach { r ->
                            FilterPill(r.label, rarityFilter == r, rarityColor(r)) { rarityFilter = if (rarityFilter == r) null else r }
                        }
                    }
                }
                Legend(listOf("Comum" to rarityColor(Rarity.COMMON), "Rara" to rarityColor(Rarity.RARE), "Épica" to rarityColor(Rarity.EPIC), "Lendária" to rarityColor(Rarity.LEGENDARY)))
                if (source.none { it.owned }) Text("Abra Equipe › Pilotos e Carros no jogo com a leitura ligada e role a lista: suas cartas aparecem aqui coloridas. As cinzas são as que você ainda não tem.",
                    fontSize = 12.sp, color = Warn)
            }
        }
        items(shown, key = { it.name + it.rarity.name + (it.slot?.name ?: "") }) { item -> AssetCard(item) { detail = item } }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) { Text("📷 Ler prints", fontSize = 12.sp) }
                    OutlinedButton(onClick = { if (tab == 1) editPart = Part(UUID.randomUUID().toString(), PartSlot.ENGINE, "") else editDriver = Driver(UUID.randomUUID().toString(), "") }, modifier = Modifier.weight(1f)) {
                        Text(if (tab == 1) "+ Peça manual" else "+ Piloto manual", fontSize = 12.sp)
                    }
                }
                Text("Dados de todos os níveis: catálogo da comunidade, temporada ${Catalog.season}.", fontSize = 10.sp, color = Muted)
            }
        }
    }

    detail?.let { it0 ->
        AssetDetailDialog(it0, onDismiss = { detail = null }, onEdit = {
            if (it0.isDriver) editDriver = g.drivers.firstOrNull { d -> d.name == it0.gameName && (d.rarity == it0.rarity || d.rarity == Rarity.UNKNOWN) }
            else editPart = g.parts.firstOrNull { p -> p.name == it0.gameName }
            detail = null
        })
    }
    editPart?.let { p -> PartDialog(p, onDismiss = { editPart = null }, onSave = { vm.store.upsertPart(it); editPart = null }, onDelete = { vm.store.removePart(p.id); editPart = null }) }
    editDriver?.let { d -> DriverDialog(d, onDismiss = { editDriver = null }, onSave = { vm.store.upsertDriver(it); editDriver = null }, onDelete = { vm.store.removeDriver(d.id); editDriver = null }) }
    if (editAccount) AccountDialog(g, onDismiss = { editAccount = false }) { ng -> vm.store.update { it.copy(garage = ng) }; editAccount = false }
}

/** Detalhe de uma carta: atributos (atual, próximo nível e máximo), gráfico por nível e custos. */
@Composable
private fun AssetDetailDialog(item: CollectionItem, onDismiss: () -> Unit, onEdit: () -> Unit) {
    val c = rarityColor(item.rarity)
    val lvl = item.level ?: 1
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(c, c.copy(alpha = 0.5f)))), contentAlignment = Alignment.Center) {
                    if (item.isDriver) HelmetIcon(Color.White, 36.dp, c.compositeDark()) else PartIcon(item.slot ?: PartSlot.ENGINE, Color.White, 36.dp, c.compositeDark())
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(item.gameName ?: item.name, fontWeight = FontWeight.Black, fontSize = 18.sp, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Pill(item.rarity.label, c)
                        item.slot?.let { Pill(it.label, Sky) }
                        Pill(if (item.owned) "Nv $lvl/${item.maxLevel}" else "Série ${item.series}", if (item.owned) Good else Muted)
                    }
                }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (item.isDriver) {
                    val def = Catalog.findDrivers(item.name).firstOrNull { it.rarity == item.rarity }
                    if (def != null) {
                        val lv = lvl.coerceIn(1, def.maxLevel)
                        val cur = def.at(lv)!!; val nx = def.at(lv + 1); val mx = def.levels.last()
                        listOf(
                            Triple("Ultrapassagem", cur.overtaking to nx?.overtaking, mx.overtaking),
                            Triple("Defesa", cur.defending to nx?.defending, mx.defending),
                            Triple("Classificação", cur.qualifying to nx?.qualifying, mx.qualifying),
                            Triple("Largada", cur.raceStart to nx?.raceStart, mx.raceStart),
                            Triple("Pneus", cur.tyreManagement to nx?.tyreManagement, mx.tyreManagement),
                        ).forEach { (l, v, m) ->
                            StatBar(l, v.first.toFloat(), if (item.owned) v.second?.toFloat() else null, m.toFloat(), 100f, c, v.first.toString(),
                                if (item.owned) v.second?.let { n -> "+${n - v.first}" } else null)
                        }
                        SectionTitle("Total por nível")
                        LevelChart(def.levels.map { it.total }, if (item.owned) lvl else null, c)
                        CostLines(item, if (item.owned) def.cards.getOrNull(lvl) else null)
                    }
                } else {
                    val def = Catalog.findPart(item.name)
                    if (def != null) {
                        val lv = lvl.coerceIn(1, def.maxLevel)
                        val cur = def.at(lv)!!; val nx = def.at(lv + 1); val mx = def.levels.last()
                        val scale = def.levels.flatMap { listOf(it.speed, it.cornering, it.powerUnit, it.qualifying, it.overtakeMode) }.max().coerceAtLeast(10).toFloat() * 1.1f
                        listOf(
                            Triple("Velocidade", cur.speed to nx?.speed, mx.speed),
                            Triple("Curvas", cur.cornering to nx?.cornering, mx.cornering),
                            Triple("Unidade de potência", cur.powerUnit to nx?.powerUnit, mx.powerUnit),
                            Triple("Classificação", cur.qualifying to nx?.qualifying, mx.qualifying),
                            Triple("Overtake", cur.overtakeMode to nx?.overtakeMode, mx.overtakeMode),
                        ).filter { it.third > 0 }.forEach { (l, v, m) ->
                            StatBar(l, v.first.toFloat(), if (item.owned) v.second?.toFloat() else null, m.toFloat(), scale, c, v.first.toString(),
                                if (item.owned) v.second?.let { n -> "+${n - v.first}" } else null)
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text("Pit stop", fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
                            Text("${cur.pitStop}s" + (nx?.let { if (item.owned) "  → ${it.pitStop}s" else "" } ?: ""), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        SectionTitle("Total por nível")
                        LevelChart(def.levels.map { it.total }, if (item.owned) lvl else null, c)
                        CostLines(item, if (item.owned) Catalog.cardSteps.getOrNull(lvl - 1) else null)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        dismissButton = { if (item.owned) TextButton(onClick = onEdit) { Text("Corrigir") } },
    )
}

@Composable
private fun CostLines(item: CollectionItem, cardsNext: Int?) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(PanelHi).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!item.owned) {
            Text("🔒 Você ainda não tem esta carta", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
            Text("Aparece a partir da Série ${item.series} (caixotes, loja e eventos).", fontSize = 12.sp, color = Muted)
            Text("No máximo: total ${item.maxTotal}", fontSize = 12.sp, color = Muted)
        } else if ((item.level ?: 0) >= item.maxLevel) {
            Text("🏆 Nível máximo", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Gold)
        } else {
            Text("Próximo nível", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BigNumber("${cardsNext ?: item.cardsNeeded}", "cartas", Sky, Modifier.weight(1f))
                BigNumber(item.nextCoins?.let { formatK(it) } ?: "?", "moedas", Gold, Modifier.weight(1f))
                BigNumber(item.nextTotal?.let { "+${it - item.total}" } ?: "?", "atributos", Good, Modifier.weight(1f))
            }
            item.coinsToMax?.let { Text("Até o nível ${item.maxLevel}: ${formatK(it)} moedas • total ${item.maxTotal}", fontSize = 12.sp, color = Muted) }
            if (item.cardsNeeded > 0) {
                ProgressLine(item.cardsOwned.toFloat() / item.cardsNeeded, if (item.cardsOwned >= item.cardsNeeded) Good else Sky)
                Text("${item.cardsOwned}/${item.cardsNeeded} cartas" + if (item.cardsOwned >= item.cardsNeeded) " • pode evoluir agora" else " • faltam ${item.cardsNeeded - item.cardsOwned}",
                    fontSize = 12.sp, color = if (item.cardsOwned >= item.cardsNeeded) Good else Muted)
            }
        }
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
