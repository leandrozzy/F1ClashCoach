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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
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

/** Abas iguais às do jogo. */
private enum class GTab(val label: String) { DRIVERS("Pilotos"), LEGENDS("Lendários"), SPECIALS("Especiais"), CARS("Carros"), BOOSTERS("Reforços") }

@Composable
fun GarageScreen(vm: MainViewModel, modifier: Modifier) {
    val data by vm.store.data.collectAsState()
    val g = data.garage
    var tab by remember { mutableStateOf(GTab.DRIVERS) }
    var own by remember { mutableStateOf(Own.ALL) }
    var detail by remember { mutableStateOf<CollectionItem?>(null) }
    var editPart by remember { mutableStateOf<Part?>(null) }
    var editDriver by remember { mutableStateOf<Driver?>(null) }
    var editAccount by remember { mutableStateOf(false) }

    val drivers = remember(g) { Catalog.driverCollection(g) }
    val parts = remember(g) { Catalog.partCollection(g) }
    val normal = drivers.filter { it.rarity != Rarity.LEGENDARY && it.rarity != Rarity.SPECIAL }
    val legends = drivers.filter { it.rarity == Rarity.LEGENDARY }
    val specials = drivers.filter { it.rarity == Rarity.SPECIAL }
    fun keep(i: CollectionItem) = when (own) {
        Own.ALL -> true
        Own.MINE -> i.owned
        Own.MISSING -> !i.owned
        Own.READY -> i.owned && i.cardsNeeded > 0 && i.cardsOwned >= i.cardsNeeded && (i.level ?: 0) < i.maxLevel
    }
    val order = compareByDescending<CollectionItem> { it.owned }.thenByDescending { it.equipped }.thenBy { it.series }.thenByDescending { it.level ?: 0 }
    // grupos como no jogo: raridade (pilotos) ou tipo de peça (carros), cada um com "tenho/total"
    val groups: List<Pair<String, List<CollectionItem>>> = when (tab) {
        GTab.DRIVERS -> listOf(Rarity.COMMON, Rarity.RARE, Rarity.EPIC, Rarity.UNKNOWN).map { r ->
            (if (r == Rarity.UNKNOWN) "Raridade a confirmar" else r.label + "s") to normal.filter { it.rarity == r } }
        GTab.LEGENDS -> listOf("Pilotos lendários" to legends)
        GTab.SPECIALS -> specials.groupBy { it.edition.ifBlank { "Edições especiais" } }.let { m ->
            // as duas coleções do jogo aparecem mesmo sem carta (como lá: 0/8)
            (listOf("Escolhas do Paddock", "Escolhas do Paddock: Turbo") + m.keys).distinct().map { it to (m[it] ?: emptyList()) } }
        GTab.CARS -> PartSlot.entries.map { sl -> sl.label to parts.filter { it.slot == sl } }
        GTab.BOOSTERS -> emptyList()
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4), modifier = modifier.fillMaxSize().background(Lavender),
        contentPadding = PaddingValues(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ---- Escalação e pontuação (topo, como no jogo) ----
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Navy).clickable { editAccount = true }.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("PONTUAÇÃO DA EQUIPE", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Muted, modifier = Modifier.weight(1f))
                    Text(if (g.teamScore > 0) "${g.teamScore}" else "—", fontSize = 22.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = Color.White)
                }
                g.carTotals?.let { t ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BigNumber("${t.speed}", "velocid.", Color.White, Modifier.weight(1f))
                        BigNumber("${t.cornering}", "curvas", Color.White, Modifier.weight(1f))
                        BigNumber("${t.powerUnit}", "potência", Color.White, Modifier.weight(1f))
                        BigNumber("${t.qualifying}", "classif.", Color.White, Modifier.weight(1f))
                        BigNumber(if (t.pitStop > 0) "%.2fs".format(t.pitStop) else "—", "pit", Color.White, Modifier.weight(1f))
                    }
                }
            }
        }
        // ---- Abas ----
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Navy).padding(3.dp)) {
                    GTab.entries.forEach { t ->
                        val (count, total) = when (t) {
                            GTab.DRIVERS -> normal.count { it.owned } to normal.size
                            GTab.LEGENDS -> legends.count { it.owned } to 23
                            GTab.SPECIALS -> specials.size to 16
                            GTab.CARS -> parts.count { it.owned } to parts.size
                            GTab.BOOSTERS -> g.boosters.values.sum() to 0
                        }
                        val sel = tab == t
                        val tint = when (t) { GTab.LEGENDS -> Gold; GTab.SPECIALS -> Color(0xFF8B3FE0); else -> Color.White }
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (sel) Color.White else Color.Transparent).clickable { tab = t; own = Own.ALL }.padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(t.label, fontSize = 10.sp, fontWeight = FontWeight.Black, color = if (sel) Ink else tint, maxLines = 1)
                            Text(if (total > 0) "$count/$total" else "$count", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (sel) InkMuted else Muted)
                        }
                    }
                }
                if (tab != GTab.BOOSTERS) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Own.entries.forEach { o -> FilterPill(o.label, own == o, Navy) { own = o } }
                }
                val empty = when (tab) { GTab.DRIVERS -> normal.none { it.owned }; GTab.LEGENDS -> legends.none { it.owned }; GTab.SPECIALS -> specials.isEmpty(); GTab.CARS -> parts.none { it.owned }; GTab.BOOSTERS -> g.boosters.isEmpty() }
                if (empty) Text(
                    when (tab) {
                        GTab.SPECIALS -> "Abra Equipe › Pilotos › Edições Especiais no jogo com a leitura ligada."
                        GTab.LEGENDS -> "Abra Equipe › Pilotos › Pilotos Lendários no jogo e role a lista."
                        GTab.BOOSTERS -> "Abra Equipe › Reforços no jogo e role a lista até o fim."
                        GTab.CARS -> "Abra Equipe › Carros no jogo e role a lista até o fim."
                        else -> "Abra Equipe › Pilotos no jogo e role a lista até o fim: suas cartas aparecem aqui com foto."
                    }, fontSize = 12.sp, color = InkMuted)
            }
        }
        if (tab == GTab.BOOSTERS) {
            val owned = com.leandro.f1clashcoach.core.engine.Boosters.owned(g.boosters).sortedByDescending { it.count }
            items(owned, span = { GridItemSpan(2) }, key = { "b_" + it.name }) { b -> BoosterTile(b) }
        }
        groups.forEach { (title, list0) ->
            val list = list0.filter { keep(it) }.sortedWith(order)
            // tamanho de cada coleção especial no jogo (8 cartas cada); as outras: o que o catálogo tem
            val total = if (tab == GTab.SPECIALS && title.startsWith("Escolhas do Paddock")) 8 else list0.size
            if (tab != GTab.SPECIALS && (list0.isEmpty() || list.isEmpty())) return@forEach
            item(span = { GridItemSpan(maxLineSpan) }, key = "h_$title") {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Navy).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    val slot = PartSlot.entries.firstOrNull { it.label == title }
                    if (slot != null) { PartIcon(slot, F1Red, 16.dp); Spacer(Modifier.width(6.dp)) }
                    Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = Color.White, modifier = Modifier.weight(1f))
                    Text("${list0.count { it.owned }}/$total", fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color.White)
                }
            }
            items(list, key = { it.name + it.rarity.name + it.edition + (it.slot?.name ?: "") }) { item -> AssetCard(item) { detail = item } }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Atributos e custos de todos os níveis: catálogo da comunidade, temporada ${Catalog.season}. Toque numa carta para ver detalhes.",
                fontSize = 10.sp, color = InkMuted, modifier = Modifier.padding(top = 8.dp, bottom = 40.dp))
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

/** Reforço como no jogo: garrafa na cor do bônus principal, os 3 bônus e a quantidade. */
@Composable
private fun BoosterTile(b: com.leandro.f1clashcoach.core.engine.BoosterOwned) {
    val main = b.def?.effects?.maxByOrNull { it.value }?.key
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Navy).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        BottleIcon(main?.let { boostColor(it) } ?: Muted, 34.dp)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(b.name, fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            b.def?.effects?.entries?.sortedByDescending { it.value }?.forEach { (st, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(boostColor(st)))
                    Text(" ${st.label} +$v", fontSize = 10.sp, color = Muted)
                }
            } ?: Text("bônus não catalogado", fontSize = 10.sp, color = Muted)
        }
        Text("${b.count}", fontSize = 20.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = Color.White)
    }
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
