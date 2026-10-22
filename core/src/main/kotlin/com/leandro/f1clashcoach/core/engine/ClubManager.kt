package com.leandro.f1clashcoach.core.engine

import kotlinx.serialization.Serializable

@Serializable
data class RepPoint(val at: Long, val rep: Long)

/** Membro do clube acompanhado ao longo do tempo (cada leitura da aba Membros vira um ponto). */
@Serializable
data class ClubMember(
    val key: String,
    val name: String,
    val role: String,
    val rep: Long,
    val firstSeen: Long,
    val lastSeen: Long,
    /** Última vez que a reputação dele subiu (sinal de que está correndo). */
    val lastGrowth: Long,
    val history: List<RepPoint> = emptyList(),
    /** Estrelas por Exibição (liga do clube): "Exibição 70" → estrelas lidas por último nela. */
    val seasons: Map<String, Long> = emptyMap(),
)

/** Regras do clube: lidas da descrição no jogo ou digitadas pelo presidente. */
@Serializable
data class ClubRules(
    val text: String = "",
    /** Reputação mínima por semana. */
    val minRepWeek: Int? = null,
    /** Dias sem correr que levam à exclusão. */
    val maxInactiveDays: Int? = null,
    /** Exclusão: estrelas na Exibição atual ≤ este valor (ex.: 0)… */
    val kickCurrentMax: Int? = null,
    /** …e na Exibição anterior abaixo deste (ex.: 100). */
    val kickPrevBelow: Int? = null,
    /** Promoção: ao menos isto (ex.: 2000)… */
    val promoteMin: Int? = null,
    /** …em tantas Exibições seguidas (ex.: 2). */
    val promoteSeasons: Int? = null,
    /** Rebaixamento de cargo abaixo disto (ex.: 500). */
    val demoteBelow: Int? = null,
) {
    val structured: Boolean get() = kickCurrentMax != null || promoteMin != null || demoteBelow != null
}

@Serializable
data class ClubState(
    val members: List<ClubMember> = emptyList(),
    val rules: ClubRules = ClubRules(),
    val memberCount: Int? = null,
    val rep: Long? = null,
    val leagueName: String? = null,
    val leagueEndsAt: Long? = null,
    /** Já avisados (nome → dia), para não repetir notificação. */
    val notified: Map<String, String> = emptyMap(),
)

data class KickAdvice(
    val name: String,
    val role: String,
    val rep: Long,
    val reason: String,
    /** 2 = agir agora, 1 = agir no fim da Exibição, 0 = acompanhar/conferir. */
    val urgency: Int,
)

data class ClubReport(
    val members: List<ClubMember>,
    val kicks: List<KickAdvice>,
    val whenToAct: String,
    val summary: List<String>,
    val weeklyRate: Map<String, Double>,
    val demotions: List<KickAdvice> = emptyList(),
    val promotions: List<KickAdvice> = emptyList(),
)

object ClubManager {
    private const val DAY = 86_400_000L
    private const val H48 = 2 * DAY

    /** Chave do membro tolerante ao OCR: sem acento, "z"="a", "i"="l" e dígitos parecidos com letras. */
    fun key(name: String): String = Txt.compact(name).map {
        when (it) { 'Z', '2' -> 'A'; 'I', '1' -> 'L'; '0' -> 'O'; '5' -> 'S'; '6' -> 'G'; '8' -> 'B'; else -> it }
    }.joinToString("")

    private fun same(a: String, b: String): Boolean {
        if (a == b) return true
        val n = minOf(a.length, b.length)
        if (n < 4) return false
        val tol = if (n >= 10) 2 else if (n >= 5) 1 else 0
        return Txt.lev(a, b, tol) <= tol
    }

    /** "Exibicao70" / "Exibição 70" → "Exibição 70". */
    fun leagueKey(name: String?): String = name?.let { Regex("""(\d{1,3})""").find(it)?.groupValues?.get(1) }?.let { "Exibição $it" } ?: "atual"

    /** Junta membros repetidos (variações de OCR já salvas), ficando com o histórico mais completo. */
    fun dedupe(members: List<ClubMember>): List<ClubMember> {
        val out = ArrayList<ClubMember>()
        for (m in members.map { it.copy(key = key(it.name)) }.sortedByDescending { it.history.size }) {
            val i = out.indexOfFirst { same(it.key, m.key) }
            if (i < 0) { out += m; continue }
            val o = out[i]
            out[i] = o.copy(
                rep = maxOf(o.rep, m.rep), firstSeen = minOf(o.firstSeen, m.firstSeen), lastSeen = maxOf(o.lastSeen, m.lastSeen),
                lastGrowth = maxOf(o.lastGrowth, m.lastGrowth), history = (o.history + m.history).sortedBy { it.at }.distinctBy { it.at },
                seasons = m.seasons + o.seasons.mapValues { (k, v) -> maxOf(v, m.seasons[k] ?: 0) },
                name = if (m.name.length > o.name.length && m.lastSeen >= o.lastSeen) m.name else o.name,
            )
        }
        return out.sortedByDescending { it.rep }
    }

    /** Junta uma leitura da aba Membros ao histórico (a lista rola: cada leitura traz só parte dos membros). */
    fun merge(state: ClubState, read: List<ClubMemberRead>, now: Long): ClubState {
        if (read.isEmpty()) return state
        val league = leagueKey(state.leagueName)
        val list = dedupe(state.members).toMutableList()
        for (r in read) {
            val k = key(r.name)
            if (k.length < 2) continue
            val i = list.indexOfFirst { same(it.key, k) }
            if (i < 0) {
                list += ClubMember(k, r.name, r.role, r.rep, now, now, now, listOf(RepPoint(now, r.rep)), mapOf(league to r.rep))
                continue
            }
            val existing = list[i]
            val grew = r.rep > existing.rep
            val reset = r.rep < existing.rep * 0.5
            val hist = (existing.history + RepPoint(now, r.rep)).filter { now - it.at < 60 * DAY }.let { h ->
                val pruned = ArrayList<RepPoint>()
                for (p in h) if (pruned.isEmpty() || p.at - pruned.last().at > 3 * 3600_000L || p === h.last()) pruned += p
                pruned
            }
            list[i] = existing.copy(
                name = if (r.name.length >= existing.name.length) r.name else existing.name,
                role = r.role, rep = r.rep, lastSeen = now,
                lastGrowth = if (grew || reset) now else existing.lastGrowth,
                history = hist, seasons = existing.seasons + (league to r.rep),
            )
        }
        return state.copy(members = list.sortedByDescending { it.rep })
    }

    private fun num(s: String): Int? {
        val m = Regex("""(\d+(?:[.,]\d+)?)\s*(K|MIL)?\b""").find(s) ?: return null
        val v = m.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return null
        return (if (m.groupValues[2].isNotEmpty()) v * 1000 else v).toInt()
    }

    /**
     * Lê a regra do clube. Entende o formato do FLA F1 ("Zero estrelas na Exibição atual e < 100 na anterior,
     * Exclusão. 2k em duas seguidas, Promoção. < 500 Rebaixado.") e o simples ("mínimo 300 por semana; 3 dias sem correr").
     */
    fun parseRules(text: String): ClubRules {
        val up = Txt.fold(text)
        var r = ClubRules(text = text.trim())
        for (clause in up.split('.', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }) {
            val kick = Regex("""EXCLU|EXPULS|REMOV|BANID""").containsMatchIn(clause)
            val promo = clause.contains("PROMO")
            val demote = clause.contains("REBAIX")
            when {
                kick && clause.contains("ATUAL") -> {
                    val cur = if (Regex("""\bZERO\b|\b0\s*ESTRELA""").containsMatchIn(clause)) 0 else num(clause.substringBefore("ATUAL"))
                    val prev = Regex("""<\s*(\d+)[^<]*ANTERIOR""").find(clause)?.groupValues?.get(1)?.toIntOrNull()
                    r = r.copy(kickCurrentMax = cur ?: 0, kickPrevBelow = prev)
                }
                promo -> r = r.copy(promoteMin = num(clause), promoteSeasons = if (Regex("""DUAS|2\s*SEGUID""").containsMatchIn(clause)) 2 else 1)
                demote -> r = r.copy(demoteBelow = Regex("""<\s*(\d+(?:[.,]\d+)?\s*K?)""").find(clause)?.groupValues?.get(1)?.let { num(it) } ?: num(clause))
            }
        }
        val minRep = Regex("""(?:MINIMO|MIN\.?|META)\D{0,25}?(\d[\d.,]{0,6})\s*(?:DE\s+)?(?:REP|REPUTACAO|ESTRELAS|★)""").find(up)
            ?: Regex("""(\d[\d.,]{0,6})\s*(?:DE\s+)?(?:REP|REPUTACAO|ESTRELAS|★)\D{0,20}(?:SEMANA|SEMANAL|MINIMO)""").find(up)
        val inactive = Regex("""(\d{1,2})\s*DIAS?\s*(?:SEM|INATIV|OFF|PARADO)""").find(up) ?: Regex("""INATIV\w*\D{0,20}(\d{1,2})\s*DIAS?""").find(up)
        return r.copy(
            minRepWeek = minRep?.groupValues?.get(1)?.let { Txt.bigNumber(it)?.toInt() },
            maxInactiveDays = inactive?.groupValues?.get(1)?.toIntOrNull(),
        )
    }

    /** Reputação por semana no período observado (null se ainda não há 2 dias de dados). */
    fun weeklyRate(m: ClubMember, now: Long): Double? {
        val win = m.history.filter { now - it.at <= 7 * DAY }
        if (win.size < 2) return null
        val span = win.last().at - win.first().at
        if (span < 2 * DAY) return null
        val gained = (win.last().rep - win.first().rep).coerceAtLeast(0)
        return gained * (7.0 * DAY / span)
    }

    private fun prevSeason(m: ClubMember, current: String): Long? = m.seasons.filterKeys { it != current }.entries.lastOrNull()?.value

    fun evaluate(state: ClubState, me: String, now: Long): ClubReport {
        val rules = state.rules
        val members = dedupe(state.members)
        val league = leagueKey(state.leagueName)
        val left = state.leagueEndsAt?.let { it - now }
        val endSoon = left != null && left in 0..H48
        val leftTxt = left?.takeIf { it > 0 }?.let { DailyBriefing.formatLeft(it) }
        val myKey = key(me)
        val rates = members.mapNotNull { m -> weeklyRate(m, now)?.let { m.key to it } }.toMap()
        val kicks = ArrayList<KickAdvice>(); val demotions = ArrayList<KickAdvice>(); val promotions = ArrayList<KickAdvice>()
        val full = (state.memberCount ?: members.size) >= 50

        for (m in members) {
            if (m.role == "Presidente" || (myKey.length >= 3 && same(m.key, myKey))) continue
            val cur = m.seasons[league] ?: m.rep
            val prev = prevSeason(m, league)
            val staff = m.role != "Membro"
            if (rules.structured) {
                rules.kickCurrentMax?.let { maxCur ->
                    if (cur <= maxCur) {
                        val below = rules.kickPrevBelow
                        when {
                            below == null -> kicks += KickAdvice(m.name, m.role, cur, "$cur ★ nesta Exibição", if (endSoon) 2 else 1)
                            prev != null && prev < below -> kicks += KickAdvice(m.name, m.role, cur, "$cur ★ nesta Exibição e $prev na anterior (< $below)", if (endSoon) 2 else 1)
                            prev == null -> kicks += KickAdvice(m.name, m.role, cur, "$cur ★ nesta Exibição; anterior ainda não lida (confira no jogo)", 0)
                        }
                    }
                }
                rules.demoteBelow?.let { b -> if (staff && cur < b) demotions += KickAdvice(m.name, m.role, cur, "$cur ★ (< $b) com cargo de ${m.role}", if (endSoon) 1 else 0) }
                rules.promoteMin?.let { pm ->
                    if (!staff || m.role == "Capitão" || m.role == "Vice-Capitão") {
                        val need = rules.promoteSeasons ?: 1
                        val ok = cur >= pm && (need < 2 || (prev != null && prev >= pm))
                        if (ok && m.role == "Membro") promotions += KickAdvice(m.name, m.role, cur, "$cur ★ agora" + (prev?.let { " e $it na anterior" } ?: "") + " (≥ $pm)", if (endSoon) 1 else 0)
                        else if (cur >= pm && need >= 2 && m.role == "Membro") promotions += KickAdvice(m.name, m.role, cur, "$cur ★ nesta Exibição: se repetir na próxima, promover", 0)
                    }
                }
            } else {
                // regra simples (mínimo por semana / dias sem correr) ou nenhuma regra
                val inactiveDays = rules.maxInactiveDays ?: 3
                val idle = (now - m.lastGrowth) / DAY.toDouble()
                val seenFor = (m.lastSeen - m.firstSeen) / DAY.toDouble()
                val rate = rates[m.key]
                when {
                    seenFor >= inactiveDays && idle >= inactiveDays -> kicks += KickAdvice(m.name, m.role, m.rep,
                        "Sem ganhar reputação há ${idle.toInt()} dias", if (staff) 0 else if (full) 2 else 1)
                    rules.minRepWeek != null && rate != null && rate < rules.minRepWeek -> kicks += KickAdvice(m.name, m.role, m.rep,
                        "Ritmo de ${rate.toInt()} rep/semana, abaixo do mínimo ${rules.minRepWeek}", if (staff) 0 else 1)
                }
            }
        }
        kicks.sortWith(compareByDescending<KickAdvice> { it.urgency }.thenBy { it.rep })
        val whenToAct = when {
            kicks.isEmpty() && demotions.isEmpty() && promotions.isEmpty() -> "Ninguém fora da regra agora. Abra Clube › Membros no jogo para eu atualizar."
            rules.structured && endSoon -> "Agora: a Exibição termina em ${leftTxt ?: "menos de 48 h"}. Aplique as exclusões, rebaixamentos e promoções."
            rules.structured && leftTxt != null -> "No fim da Exibição (faltam $leftTxt): até lá ainda dá para correr. Avise no bate-papo."
            kicks.any { it.urgency == 2 } -> "Agora: o clube está cheio (50/50) e há inativos ocupando vaga."
            else -> "Avise no bate-papo e aplique no fim da Exibição."
        }
        val summary = buildList {
            add("${state.memberCount ?: members.size}/50 membros • ${members.size} acompanhados" + (state.rep?.let { " • ★$it" } ?: ""))
            if (rules.text.isBlank()) add("Regra do clube não lida: abra a descrição do clube no jogo ou escreva a regra aqui.")
            if (rules.structured && members.none { it.seasons.size >= 2 }) add("Ainda não tenho a Exibição anterior de ninguém: a comparação completa começa na próxima Exibição.")
        }
        return ClubReport(members, kicks, whenToAct, summary, rates, demotions, promotions)
    }

    /** Resumo em texto para a IA (plano do clube). */
    fun aiContext(state: ClubState, me: String, now: Long): String {
        val r = evaluate(state, me, now)
        return buildString {
            append("CLUBE (sou o presidente): ").append(r.summary.joinToString(" • ")).append('\n')
            if (state.rules.text.isNotBlank()) append("Regras: ").append(state.rules.text.take(400)).append('\n')
            state.leagueName?.let { append("Liga: $it").append(state.leagueEndsAt?.let { e -> " (faltam ${DailyBriefing.formatLeft(e - now)})" } ?: "").append('\n') }
            append("Membros (nome, cargo, estrelas): ")
            append(r.members.take(50).joinToString("; ") { m -> "${m.name} ${m.role} ${m.rep}" })
            if (r.kicks.isNotEmpty()) append("\nExcluir: ").append(r.kicks.joinToString("; ") { "${it.name}: ${it.reason}" })
            if (r.demotions.isNotEmpty()) append("\nRebaixar: ").append(r.demotions.joinToString("; ") { "${it.name}: ${it.reason}" })
            if (r.promotions.isNotEmpty()) append("\nPromover: ").append(r.promotions.joinToString("; ") { "${it.name}: ${it.reason}" })
        }
    }
}
