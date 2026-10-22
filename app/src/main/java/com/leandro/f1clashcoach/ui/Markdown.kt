package com.leandro.f1clashcoach.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Um bloco do texto da IA: título, item de lista ou parágrafo. */
private data class MdBlock(val kind: Int, val text: AnnotatedString) // 0 parágrafo, 1 título, 2 item

/** Negrito (**x**) e itálico (*x* / _x_) em linha; o resto vai como texto. */
private fun inline(s: String, accent: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end < 0) { append(s.substring(i + 2)); i = s.length }
                else { withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent)) { append(s.substring(i + 2, end)) }; i = end + 2 }
            }
            (s[i] == '*' || s[i] == '_') && i + 1 < s.length && s[i + 1] != ' ' && (i == 0 || s[i - 1] == ' ' || s[i - 1] == '(') -> {
                val end = s.indexOf(s[i], i + 1)
                if (end < 0) { append(s[i]); i++ }
                else { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, end)) }; i = end + 1 }
            }
            s[i] == '`' -> i++
            else -> { append(s[i]); i++ }
        }
    }
}

private fun parse(md: String, accent: Color): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    for (raw in md.lines()) {
        val l = raw.trim()
        if (l.isEmpty() || Regex("""^[-*_=|:\s]{3,}$""").matches(l)) continue // linhas vazias, "---" e "|---|---|"
        when {
            l.startsWith("#") -> out += MdBlock(1, inline(l.trimStart('#').trim(), accent))
            // tabela: "| a | b |" vira "a • b"
            l.startsWith("|") -> {
                val cells = l.trim('|').split("|").map { it.trim() }.filter { it.isNotEmpty() }
                if (cells.isNotEmpty()) out += MdBlock(2, inline(cells.joinToString(" • "), accent))
            }
            Regex("""^([-*•]|\d+[.)])\s+""").containsMatchIn(l) -> {
                val num = Regex("""^(\d+)[.)]\s+""").find(l)?.groupValues?.get(1)
                val body = l.replaceFirst(Regex("""^([-*•]|\d+[.)])\s+"""), "")
                out += MdBlock(2, buildAnnotatedString { append(if (num != null) "$num. " else "• "); append(inline(body, accent)) })
            }
            else -> out += MdBlock(0, inline(l, accent))
        }
    }
    return out
}

/** Mostra o texto da IA formatado (sem "###", "**" e tabelas cruas). */
@Composable
fun MdText(md: String, modifier: Modifier = Modifier, color: Color = Color.White, accent: Color = Yellow, maxBlocks: Int = Int.MAX_VALUE) {
    val blocks = remember(md, accent) { parse(md, accent) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.take(maxBlocks).forEach { b ->
            when (b.kind) {
                1 -> Text(b.text, fontSize = 15.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = color)
                2 -> Text(b.text, fontSize = 13.sp, lineHeight = 18.sp, color = color, modifier = Modifier)
                else -> Text(b.text, fontSize = 13.sp, lineHeight = 18.sp, color = color)
            }
        }
    }
}

/** Texto da IA sem marcação, para lugares de uma linha (notificação, faixa). */
fun mdPlain(md: String): String = md.lines().map { it.trim().trimStart('#').replace("**", "").replace("`", "").trim() }
    .filter { it.isNotEmpty() && !Regex("""^[-*_=|:\s]{3,}$""").matches(it) }.joinToString("\n")
