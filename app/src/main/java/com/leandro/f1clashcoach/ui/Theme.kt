package com.leandro.f1clashcoach.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val F1Red = Color(0xFFE10600)
val Good = Color(0xFF2ECC71)
val Warn = Color(0xFFF1C40F)
val Muted = Color(0xFF9AA7BD)
// Paleta no estilo do jogo: fundo lilás claro, painéis azul-marinho, amarelo de ação, vermelho "CORRIDA".
val Navy = Color(0xFF151A2E)
val Panel = Color(0xFF1E2339)
val PanelHi = Color(0xFF2A3050)
val Line = Color(0xFF353C5E)
val Lavender = Color(0xFFE9E7F4)
val Ink = Color(0xFF1B1F33)
val InkMuted = Color(0xFF5C6283)
val Yellow = Color(0xFFFFD100)
val Mint = Color(0xFF19D3A2)
val Sky = Color(0xFF3FA9F5)
val Gold = Color(0xFFF5C542)

private val scheme = darkColorScheme(
    primary = F1Red,
    onPrimary = Color.White,
    secondary = Color(0xFF00A3E0),
    background = Lavender,
    surface = Panel,
    surfaceVariant = PanelHi,
    surfaceContainer = Panel,
    onBackground = Ink,
    onSurface = Color(0xFFEAF0FA),
    onSurfaceVariant = Color(0xFFB8C4D9),
    outline = Line,
)

@Composable
fun CoachTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontWeight = FontWeight.Black, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontSize = 17.sp, color = Color.White, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            content()
        }
    }
}

@Composable
fun Stat(label: String, value: String, color: Color = Color.Unspecified) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = color)
        Text(label, fontSize = 11.sp, color = Muted)
    }
}

/** Título de seção direto no fundo claro (estilo do jogo: grosso e inclinado). */
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), fontWeight = FontWeight.Black, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontSize = 16.sp, color = Ink, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = InkMuted) }
    }
}
