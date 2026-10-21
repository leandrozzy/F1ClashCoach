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
)

/** Regras do clube: lidas da descrição no jogo ou digitadas pelo presidente. */
@Serializable
data class ClubRules(
    val text: String = "",
    /** Reputação mínima por semana. */
    val minRepWeek: Int? = null,
    /** Dias sem correr que levam à exclusão. */
    val maxInactiveDays: Int? = null,
)

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
    /** 2 = excluir agora, 1 = excluir no fim da liga, 0 = avisar/conversar. */
    val urgency: Int,
)

data class ClubReport(
    val members: List<ClubMember>,
    val kicks: List<KickAdvice>,
    val whenToAct: String,
    val summary: List<String>,
    val weeklyRate: Map<String, Double>,
)

object ClubManager {
    private const val DAY = 86_400_000L

    fun key(name: String): String = Txt.compact(name).replace('Z', 'A')

    /** Junta uma leitura da aba Membros ao histórico (a lista rola: cada leitura traz só parte dos membros). */
    fun merge(state: ClubState, read: List<ClubMemberRead>, now: Long): ClubState {
        if (read.isEmpty()) return state
        val byKey = state.members.associateBy { it.key }.toMutableMap()
        for (r in read) {
            val k = key(r.name)
            if (k.length < 2) continue
            // nomes lidos com 1-2 letras trocadas casam com o membro já conhecido
            val existing = byKey[k] ?: byKey.values.firstOrNull { k.length >= 5 && Txt.lev(it.key, k, 2) <= 1 }
            if (existing == null) {
                byKey[k] = ClubMember(k, r.name, r.role, r.rep, now, now, now, listOf(RepPoint(now, r.rep)))
            } else {
                val grew = r.rep > existing.rep
                // reputação caiu muito = nova liga/temporada zerou a contagem
                val reset = r.rep < existing.rep * 0.5
                val hist = (existing.history + RepPoint(now, r.rep)).let { h ->
                    // no máximo 1 ponto a cada 3 h por membro, últimos 30 dias
                    val pruned = ArrayList<RepPoint>()
                    for (p in h.filter { now - it.at < 30 * DAY }) if (pruned.isEmpty() || p.at - pruned.last().at > 3 * 3600_000L || p === h.last()) pruned += p
                    pruned
                }
                byKey.remove(existing.key)
                byKey[existing.key] = existing.copy(
                    name = if (r.name.length >= existing.name.length) r.name else existing.name,
                    role = r.role, rep = r.rep, lastSeen = now,
                    lastGrowth = if (grew || reset) now else existing.lastGrowth,
                    history = hist,
                )
            }
        }
        return state.copy(members = byKey.values.sortedByDescending { it.rep })
    }

    /** Lê números das regras: "mínimo 300 de reputação por semana", "3 dias sem correr = expulsão". */
    fun parseRules(text: String): ClubRules {
        val up = Txt.fold(text)
        val minRep = Regex("""(?:MINIMO|MIN\.?|META)\D{0,25}?(\d[\d.,]{0,6})\s*(?:DE\s+)?(?:REP|REPUTACAO|ESTRELAS|★)""").find(up)
            ?: Regex("""(\d[\d.,]{0,6})\s*(?:DE\s+)?(?:REP|REPUTACAO|ESTRELAS|★)\D{0,20}(?:SEMANA|SEMANAL|MINIMO)""").find(up)
        val inactive = Regex("""(\d{1,2})\s*DIAS?\s*(?:SEM|INATIV|OFF|PARADO)""").find(up)
            ?: Regex("""INATIV\w*\D{0,20}(\d{1,2})\s*DIAS?""").find(up)
        return ClubRules(
            text = text.trim(),
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

    fun evaluate(state: ClubState, me: String, now: Long): ClubReport {
        val rules = state.rules
        val inactiveDays = rules.maxInactiveDays ?: 3
        val members = state.members
        val full = (state.memberCount ?: members.size) >= 50
        val observedDays = members.maxOfOrNull { (it.lastSeen - it.firstSeen) / DAY.toDouble() } ?: 0.0
        val rates = members.mapNotNull { m -> weeklyRate(m, now)?.let { m.key to it } }.toMap()
        val median = members.map { it.rep }.sorted().let { if (it.isEmpty()) 0L else it[it.size / 2] }
        val myKey = key(me)
        val kicks = ArrayList<KickAdvice>()
        for (m in members) {
            if (m.role == "Presidente" || (myKey.length >= 3 && m.key == myKey)) continue
            val idle = (now - m.lastGrowth) / DAY.toDouble()
            val seenFor = (m.lastSeen - m.firstSeen) / DAY.toDouble()
            val rate = rates[m.key]
            val staff = m.role != "Membro"
            when {
                seenFor >= inactiveDays && idle >= inactiveDays -> kicks += KickAdvice(m.name, m.role, m.rep,
                    "Sem ganhar reputação há ${idle.toInt()} dias" + (if (rules.maxInactiveDays != null) " (regra: ${rules.maxInactiveDays} dias)" else ""),
                    if (staff) 0 else if (full) 2 else 1)
                rules.minRepWeek != null && rate != null && rate < rules.minRepWeek -> kicks += KickAdvice(m.name, m.role, m.rep,
                    "Ritmo de ${rate.toInt()} rep/semana, abaixo do mínimo ${rules.minRepWeek}", if (staff) 0 else 1)
                rules.minRepWeek == null && observedDays >= 5 && median > 0 && m.rep < median * 0.2 && rate != null && rate < 50 -> kicks += KickAdvice(m.name, m.role, m.rep,
                    "Reputação ${m.rep} (menos de 20% da mediana do clube, $median)", 0)
            }
        }
        kicks.sortWith(compareByDescending<KickAdvice> { it.urgency }.thenBy { it.rep })
        val left = state.leagueEndsAt?.let { it - now }
        val whenToAct = when {
            kicks.isEmpty() -> "Ninguém para excluir agora. Continuo acompanhando a cada leitura da aba Membros."
            kicks.any { it.urgency == 2 } -> "Agora: o clube está cheio (50/50) e há inativos ocupando vaga de quem corre."
            left != null && left in 0..DAY -> "Agora: a liga termina em menos de 24 h; a reputação deles já foi contada — abra as vagas para a próxima liga."
            left != null -> "No fim da liga (faltam ${DailyBriefing.formatLeft(left)}): a reputação deles ainda conta até lá. Avise antes no bate-papo."
            else -> "Avise no bate-papo e exclua quem não reagir em 24 h."
        }
        val summary = buildList {
            add("${state.memberCount ?: members.size}/50 membros • ${members.size} acompanhados" + (state.rep?.let { " • ★$it" } ?: ""))
            if (observedDays < 2) add("Abra a aba Membros no jogo todo dia (rolando até o fim) para eu medir quem está correndo.")
            if (rules.text.isBlank()) add("Regra do clube não lida: abra a descrição do clube no jogo ou digite a regra aqui.")
            rates.values.takeIf { it.isNotEmpty() }?.let { add("Média: ${it.average().toInt()} rep/semana por membro") }
        }
        return ClubReport(members, kicks, whenToAct, summary, rates)
    }

    /** Resumo em texto para a IA (plano do clube). */
    fun aiContext(state: ClubState, me: String, now: Long): String {
        val r = evaluate(state, me, now)
        return buildString {
            append("CLUBE (sou o presidente): ").append(r.summary.joinToString(" • ")).append('\n')
            if (state.rules.text.isNotBlank()) append("Regras: ").append(state.rules.text.take(400)).append('\n')
            state.leagueName?.let { append("Liga: $it").append(state.leagueEndsAt?.let { e -> " (faltam ${DailyBriefing.formatLeft(e - now)})" } ?: "").append('\n') }
            append("Membros (nome, cargo, rep, rep/semana): ")
            append(r.members.take(50).joinToString("; ") { m -> "${m.name} ${m.role} ${m.rep}" + (r.weeklyRate[m.key]?.let { " ${it.toInt()}/sem" } ?: "") })
            if (r.kicks.isNotEmpty()) append("\nCandidatos a exclusão: ").append(r.kicks.joinToString("; ") { "${it.name}: ${it.reason}" })
        }
    }
}
