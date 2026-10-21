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
val Muted = Color(0xFF9AA0A6)

private val scheme = darkColorScheme(
    primary = F1Red,
    onPrimary = Color.White,
    secondary = Color(0xFF00A3E0),
    background = Color(0xFF0F1115),
    surface = Color(0xFF171A21),
    surfaceVariant = Color(0xFF20242D),
    onBackground = Color(0xFFECEDEF),
    onSurface = Color(0xFFECEDEF),
)

@Composable
fun CoachTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
