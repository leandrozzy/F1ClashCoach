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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Anel de progresso (ex.: pontuação da equipe x mínimo da Série 12). */
@Composable
fun GaugeRing(fraction: Float, value: String, label: String, color: Color, size: Dp = 96.dp, stroke: Dp = 10.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val sw = stroke.toPx()
            val d = this.size.minDimension - sw
            val tl = Offset(sw / 2, sw / 2)
            drawArc(Color.White.copy(alpha = 0.10f), 135f, 270f, false, tl, Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
            drawArc(color, 135f, 270f * fraction.coerceIn(0f, 1f), false, tl, Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Black, fontSize = 18.sp, color = Color.White)
            Text(label, fontSize = 10.sp, color = Muted, textAlign = TextAlign.Center)
        }
    }
}

/** Rosca de vitórias x derrotas. */
@Composable
fun WinDonut(wins: Int, losses: Int, size: Dp = 96.dp) {
    val total = wins + losses
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val sw = 12.dp.toPx()
            val d = this.size.minDimension - sw
            val tl = Offset(sw / 2, sw / 2)
            if (total == 0) {
                drawArc(Color.White.copy(alpha = 0.10f), 0f, 360f, false, tl, Size(d, d), style = Stroke(sw))
            } else {
                val w = 360f * wins / total
                drawArc(Good, -90f, w, false, tl, Size(d, d), style = Stroke(sw))
                drawArc(F1Red, -90f + w, 360f - w, false, tl, Size(d, d), style = Stroke(sw))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (total == 0) "—" else "${wins * 100 / total}%", fontWeight = FontWeight.Black, fontSize = 18.sp, color = Color.White)
            Text("vitórias", fontSize = 10.sp, color = Muted)
        }
    }
}

/** Barra de progresso arredondada com rótulo. */
@Composable
fun ProgressLine(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.7f), color))))
    }
}

/** Etiqueta colorida (status). */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color,
        modifier = modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 3.dp))
}

/** Cartão de destaque com degradê (topo da tela inicial). */
@Composable
fun HeroCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF5A0A0A), Color(0xFF1B2740), Navy)))
            .border(1.dp, F1Red.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
            .padding(16.dp),
    ) { content() }
}

/** Ícone redondo com emoji. */
@Composable
fun EmojiBadge(emoji: String, color: Color = Sky, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = (size.value * 0.45f).sp)
    }
}

/** Linha de ação: ícone, título, explicação e seta. Toda a linha é clicável. */
@Composable
fun ActionRow(emoji: String, title: String, detail: String, color: Color = Sky, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PanelHi)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(36.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(8.dp))
        EmojiBadge(emoji, color, 38.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
            if (detail.isNotBlank()) Text(detail, fontSize = 12.sp, color = Muted, lineHeight = 16.sp)
        }
        if (trailing != null) trailing() else if (onClick != null) Text("›", fontSize = 24.sp, color = Muted, modifier = Modifier.padding(start = 6.dp))
    }
}

/** Passo do guia inicial: número/✓, título, explicação e botão. */
@Composable
fun SetupStep(n: Int, done: Boolean, title: String, detail: String, button: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(if (done) Good else F1Red), contentAlignment = Alignment.Center) {
            Text(if (done) "✓" else n.toString(), color = Color.White, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (done) Muted else Color.White)
            Text(detail, fontSize = 12.sp, color = Muted, lineHeight = 16.sp)
        }
        if (!done && button != null) {
            Text(button, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(F1Red).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

/** Escada de séries até a S12: círculos ligados, cor por situação. */
@Composable
fun SeriesLadder(current: Int, from: Int = 7, to: Int = 12, scoreOk: (Int) -> Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        for (n in from..to) {
            val color = when {
                n < current -> Good
                n == current -> Sky
                n == to -> Gold
                else -> Color.White.copy(alpha = 0.25f)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(if (n == current || n == to) 36.dp else 28.dp).clip(CircleShape)
                        .background(if (n <= current) color else color.copy(alpha = 0.18f))
                        .border(2.dp, color, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (n < current) "✓" else if (n == to) "🏆" else n.toString(), fontSize = if (n == to) 15.sp else 12.sp,
                        fontWeight = FontWeight.Bold, color = Color.White)
                }
                Text("S$n", fontSize = 10.sp, color = if (scoreOk(n)) Good else Muted)
            }
            if (n < to) Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(50)).background(if (n < current) Good else Color.White.copy(alpha = 0.15f)))
        }
    }
}

/** Últimos resultados como quadradinhos verdes/vermelhos (mais recente à direita). */
@Composable
fun ResultStrip(results: List<Boolean>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        results.forEach { w ->
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (w) Good else F1Red), contentAlignment = Alignment.Center) {
                Text(if (w) "V" else "D", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color.White)
            }
        }
    }
}

/** Número grande + legenda, para fileiras de números. */
@Composable
fun BigNumber(value: String, label: String, color: Color = Color.White, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(PanelHi).padding(vertical = 8.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Black, fontSize = 17.sp, color = color, maxLines = 1)
        Text(label, fontSize = 10.sp, color = Muted, maxLines = 1)
    }
}
