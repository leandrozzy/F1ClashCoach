package com.leandro.f1clashcoach.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leandro.f1clashcoach.core.model.CollectionItem
import com.leandro.f1clashcoach.core.model.PartSlot
import com.leandro.f1clashcoach.core.model.Rarity
import kotlin.math.cos
import kotlin.math.sin

/** Cores das cartas do jogo. */
fun rarityColor(r: Rarity): Color = when (r) {
    Rarity.COMMON -> Color(0xFF2F7BEA)
    Rarity.RARE -> Color(0xFFF2811D)
    Rarity.EPIC -> Color(0xFF8B3FE0)
    Rarity.LEGENDARY -> Color(0xFFE6B422)
    Rarity.SPECIAL -> Color(0xFF1ABC9C)
    Rarity.UNKNOWN -> Color(0xFF5A6478)
}

private fun DrawScope.rr(s: Float, x: Float, y: Float, w: Float, h: Float, r: Float, c: Color) =
    drawRoundRect(c, Offset(x * s, y * s), Size(w * s, h * s), CornerRadius(r * s, r * s))

/** Ícone desenhado de cada tipo de peça (grade 24×24). */
@Composable
fun PartIcon(slot: PartSlot, color: Color = Color.White, size: Dp = 32.dp, bg: Color = Color.Transparent) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension / 24f
        when (slot) {
            PartSlot.FRONT_WING -> {
                val p = Path().apply { moveTo(10 * s, 4 * s); lineTo(14 * s, 4 * s); lineTo(12 * s, 9 * s); close() }
                drawPath(p, color)
                rr(s, 2f, 9f, 5f, 12f, 1f, color); rr(s, 17f, 9f, 5f, 12f, 1f, color)
                rr(s, 2f, 18f, 20f, 3f, 1f, color); rr(s, 7f, 14f, 10f, 2.5f, 0.5f, color)
            }
            PartSlot.REAR_WING -> {
                rr(s, 1f, 3f, 22f, 5f, 1f, color); rr(s, 1f, 8f, 4.5f, 13f, 1f, color)
                rr(s, 18.5f, 8f, 4.5f, 13f, 1f, color); rr(s, 9f, 8f, 6f, 4.5f, 0.5f, color)
            }
            PartSlot.BRAKES -> {
                drawArc(color, 30f, 300f, false, Offset(3 * s, 3 * s), Size(18 * s, 18 * s), style = Stroke(2.5f * s, cap = StrokeCap.Round))
                drawCircle(color, 5 * s, Offset(12 * s, 12 * s), style = Stroke(1.5f * s))
                drawCircle(color, 2 * s, Offset(12 * s, 12 * s))
            }
            PartSlot.SUSPENSION -> {
                rotate(-45f, Offset(12 * s, 12 * s)) { rr(s, 5f, 10.5f, 14f, 3f, 1.5f, color) }
                drawCircle(color, 2.8f * s, Offset(6 * s, 18 * s))
                rr(s, 16f, 3.5f, 5.5f, 4.5f, 0.5f, color)
            }
            PartSlot.GEARBOX -> {
                val p = Path()
                for (i in 0 until 24) {
                    val r = if (i % 2 == 0) 10f else 7.5f
                    val a = Math.toRadians(i * 15.0)
                    val x = (12 + r * cos(a)).toFloat() * s; val y = (12 + r * sin(a)).toFloat() * s
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                drawPath(p, color)
                drawCircle(if (bg == Color.Transparent) Color.Black.copy(alpha = 0.55f) else bg, 4 * s, Offset(12 * s, 12 * s))
                drawCircle(color, 1.6f * s, Offset(12 * s, 12 * s))
            }
            PartSlot.ENGINE -> {
                rr(s, 2f, 4f, 9f, 5.5f, 1.5f, color); rr(s, 2f, 9.5f, 20f, 12f, 2f, color)
                rr(s, 13f, 6f, 6f, 3.5f, 0.8f, color)
                val hole = if (bg == Color.Transparent) Color.Black.copy(alpha = 0.45f) else bg
                for (i in 0..2) rr(s, 5f + i * 5f, 13f, 3f, 5f, 0.6f, hole)
            }
            PartSlot.BATTERY -> {
                rr(s, 4.5f, 5f, 4.5f, 3.5f, 1f, color); rr(s, 15f, 5f, 4.5f, 3.5f, 1f, color)
                rr(s, 2f, 8f, 20f, 13f, 2f, color)
                val hole = if (bg == Color.Transparent) Color.Black.copy(alpha = 0.55f) else bg
                rr(s, 4f, 14.5f, 5.5f, 2f, 0.6f, hole); rr(s, 14.5f, 14.5f, 5.5f, 2f, 0.6f, hole); rr(s, 16.35f, 12.5f, 1.8f, 6f, 0.6f, hole)
            }
        }
    }
}

/** Capacete de piloto desenhado. */
@Composable
fun HelmetIcon(color: Color = Color.White, size: Dp = 32.dp, visor: Color = Color.Black.copy(alpha = 0.55f)) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val shell = Path().apply {
            moveTo(3 * s, 16 * s)
            cubicTo(3 * s, 7 * s, 9 * s, 3 * s, 14 * s, 3 * s)
            cubicTo(19 * s, 3 * s, 22 * s, 7 * s, 22 * s, 12 * s)
            lineTo(22 * s, 18 * s)
            cubicTo(22 * s, 20 * s, 20.5f * s, 21 * s, 19 * s, 21 * s)
            lineTo(6 * s, 21 * s)
            cubicTo(4 * s, 21 * s, 3 * s, 19 * s, 3 * s, 16 * s)
            close()
        }
        drawPath(shell, color)
        rr(s, 11f, 9f, 11f, 5f, 2.5f, visor)
        rr(s, 4f, 17f, 15f, 1.6f, 0.8f, visor.copy(alpha = 0.35f))
    }
}

/** Barra de atributo: valor atual, ganho do próximo nível (mais claro) e o máximo possível (traço). */
@Composable
fun StatBar(label: String, value: Float, next: Float?, max: Float, scale: Float, color: Color, valueText: String, gainText: String?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
            Text(valueText, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (gainText != null) Text("  $gainText", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Good)
        }
        Canvas(Modifier.fillMaxWidth().height(10.dp).padding(top = 2.dp)) {
            val w = size.width; val h = size.height
            drawRoundRect(Color.White.copy(alpha = 0.08f), cornerRadius = CornerRadius(h / 2, h / 2))
            val nx = ((next ?: value) / scale).coerceIn(0f, 1f) * w
            if (next != null && next > value) drawRoundRect(Good.copy(alpha = 0.55f), size = Size(nx, h), cornerRadius = CornerRadius(h / 2, h / 2))
            drawRoundRect(color, size = Size((value / scale).coerceIn(0f, 1f) * w, h), cornerRadius = CornerRadius(h / 2, h / 2))
            val mx = (max / scale).coerceIn(0f, 1f) * w
            drawLine(Color.White.copy(alpha = 0.7f), Offset(mx, 0f), Offset(mx, h), strokeWidth = 2.dp.toPx())
        }
    }
}

/** Gráfico do total de atributos por nível, com o seu nível destacado. */
@Composable
fun LevelChart(totals: List<Int>, current: Int?, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(110.dp)) {
        if (totals.size < 2) return@Canvas
        val w = size.width; val h = size.height
        val pad = 14.dp.toPx()
        val minV = totals.min().toFloat(); val maxV = totals.max().toFloat().coerceAtLeast(minV + 1)
        fun pt(i: Int) = Offset(pad + (w - 2 * pad) * i / (totals.size - 1), h - pad - (h - 2 * pad) * (totals[i] - minV) / (maxV - minV))
        for (k in 0..3) {
            val y = pad + (h - 2 * pad) * k / 3
            drawLine(Color.White.copy(alpha = 0.06f), Offset(pad, y), Offset(w - pad, y))
        }
        val area = Path().apply {
            moveTo(pt(0).x, h - pad)
            totals.indices.forEach { lineTo(pt(it).x, pt(it).y) }
            lineTo(pt(totals.size - 1).x, h - pad); close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
        val line = Path().apply { totals.indices.forEach { if (it == 0) moveTo(pt(it).x, pt(it).y) else lineTo(pt(it).x, pt(it).y) } }
        drawPath(line, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        totals.indices.forEach { i ->
            val isCur = current == i + 1
            drawCircle(if (isCur) Color.White else color, if (isCur) 6.dp.toPx() else 3.dp.toPx(), pt(i))
            if (isCur) drawCircle(color, 3.5.dp.toPx(), pt(i))
        }
    }
}

/** Carta da coleção: cor da raridade, ícone, nível, cartas e selos. Cinza com cadeado se você não tem. */
@Composable
fun AssetCard(item: CollectionItem, onClick: () -> Unit) {
    val c = rarityColor(item.rarity)
    val canUp = item.owned && item.cardsNeeded > 0 && item.cardsOwned >= item.cardsNeeded && (item.level ?: 0) < item.maxLevel
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .border(if (item.equipped) 2.dp else 1.dp, if (item.equipped) Good else c.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .clickable { onClick() },
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.05f)
                .background(Brush.linearGradient(listOf(c.copy(alpha = if (item.owned) 0.95f else 0.25f), c.copy(alpha = if (item.owned) 0.45f else 0.12f)))),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.alpha(if (item.owned) 1f else 0.45f)) {
                if (item.isDriver) HelmetIcon(Color.White, 46.dp, c.copy(alpha = 0.9f).compositeDark())
                else PartIcon(item.slot ?: PartSlot.ENGINE, Color.White, 46.dp, c.compositeDark())
            }
            if (item.owned) {
                Text("${item.level ?: "?"}", fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(5.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 6.dp, vertical = 1.dp))
            } else {
                Text("🔒 S${item.series}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(5.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 5.dp, vertical = 1.dp))
            }
            if (canUp) Text("⬆", fontSize = 13.sp, fontWeight = FontWeight.Black, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp).clip(CircleShape).background(Good), textAlign = TextAlign.Center)
            if (item.featured) Text("+10%", fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color.Black,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(5.dp)).background(Gold).padding(horizontal = 4.dp))
            if (item.equipped) Text("✓", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(18.dp).clip(CircleShape).background(Good), textAlign = TextAlign.Center)
        }
        Column(Modifier.padding(horizontal = 6.dp, vertical = 5.dp)) {
            Text(item.gameName ?: item.name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.owned && item.cardsNeeded > 0) {
                ProgressLine(item.cardsOwned.toFloat() / item.cardsNeeded, if (canUp) Good else Sky, height = 5.dp)
                Text("${item.cardsOwned}/${item.cardsNeeded}", fontSize = 9.sp, color = Muted)
            } else if (item.owned) {
                Text(if ((item.level ?: 0) >= item.maxLevel) "Nível máximo" else "Cartas ?", fontSize = 9.sp, color = Muted)
            } else {
                Text("Não tenho", fontSize = 9.sp, color = Muted)
            }
        }
    }
}

/** Escurece a cor da raridade (fundo do visor/furos dos ícones). */
fun Color.compositeDark(): Color = Color(red * 0.35f, green * 0.35f, blue * 0.35f, 1f)

/** Chip de filtro com ícone opcional. */
@Composable
fun FilterPill(text: String, selected: Boolean, color: Color = F1Red, icon: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) color else PanelHi).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { icon(); Spacer(Modifier.width(5.dp)) }
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

/** Mini barra horizontal (comparativos). */
@Composable
fun MiniBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.height(6.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f))) {
        Box(Modifier.fillMaxSize().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(RoundedCornerShape(50)).background(color))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
fun Legend(items: List<Pair<String, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (t, c) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                Spacer(Modifier.width(4.dp))
                Text(t, fontSize = 11.sp, color = Muted)
            }
        }
    }
}
