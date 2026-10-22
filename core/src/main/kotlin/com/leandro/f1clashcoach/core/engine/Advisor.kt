package com.leandro.f1clashcoach.core.engine

import com.leandro.f1clashcoach.core.model.ActiveEvent
import com.leandro.f1clashcoach.core.model.Garage
import com.leandro.f1clashcoach.core.model.GrandPrixState
import com.leandro.f1clashcoach.core.model.Objective
import com.leandro.f1clashcoach.core.model.RaceRecord
import com.leandro.f1clashcoach.core.model.SeriesInfo
import com.leandro.f1clashcoach.core.model.TyreObservation

/** Onde o cartão leva quando tocado. */
enum class AdviceTarget { GAME, GARAGE, EVOLVE, RACE, CLUB, AI, NONE }

/** Conselho pronto, gerado sozinho a partir do que foi lido do jogo (sem o usuário perguntar). */
data class AdviceCard(
    val id: String,
    val icon: String,
    val title: String,
    val lines: List<String>,
    /** 0 = mais urgente. */
    val priority: Int,
    val target: AdviceTarget,
    /** Quando não há dado suficiente: o que abrir no jogo para o conselho aparecer. */
    val missing: String? = null,
)

object Advisor {
    private const val H = 3_600_000L

    fun cards(
        garage: Garage,
        races: List<RaceRecord>,
        events: List<ActiveEvent>,
        objectives: List<Objective>,
        gp: GrandPrixState?,
        club: ClubState,
        series: List<SeriesInfo>,
        flags: Int?,
        tyreObs: List<TyreObservation>,
        lastMetaTime: Long,
        now: Long,
    ): List<AdviceCard> {
        val out = ArrayList<AdviceCard>()

        // 1) Moedas: o que evoluir agora (só o que cabe no saldo), por ganho por moeda.
        val up = UpgradePlanner.plan(garage)
        if (garage.parts.isEmpty() && garage.drivers.isEmpty()) {
            out += AdviceCard("up", "⬆", "Onde gastar suas moedas", emptyList(), 3, AdviceTarget.GAME, "Abra Equipe › Pilotos e Carros no jogo")
        } else if (up.doNow.isNotEmpty()) {
            out += AdviceCard("up", "⬆", "Evolua agora (${up.doNow.size} cabem nas suas moedas)",
                up.doNow.take(3).map { a -> "${a.name}: nv ${a.level}→${a.level + 1} • +${"%.0f".format(a.gain)} atributos • ${money(a.coins.toLong())}" } +
                    listOf("Sobram ${money(up.coinsLeft)}"), 1, AdviceTarget.EVOLVE)
        } else if (up.collectCards.isNotEmpty()) {
            out += AdviceCard("up", "🃏", "Junte cartas destes", up.collectCards.take(3).map { "${it.name}: ${it.reason}" }, 3, AdviceTarget.EVOLVE)
        }

        // 2) Grand Prix da semana: escalação permitida e corridas que faltam.
        if (gp != null && (gp.roundEndsAt == null || gp.roundEndsAt > now)) {
            val left = gp.roundEndsAt?.let { it - now }
            val plan = GpPlanner.plan(garage, gp)
            out += AdviceCard("gp", "🏆", "Grand Prix: ${gp.name}" + (left?.let { " • faltam ${DailyBriefing.formatLeft(it)}" } ?: ""),
                plan.lines.filterNot { it.trim().startsWith("Nível: ?") }.take(4), if (left != null && left < 12 * H) 0 else 1, AdviceTarget.GAME)
        }

        // 3) Reforços: plano da próxima corrida com o que você tem.
        if (garage.boosters.isEmpty()) {
            out += AdviceCard("boost", "🧪", "Reforços da próxima corrida", emptyList(), 4, AdviceTarget.GAME, "Abra Equipe › Reforços no jogo")
        } else {
            val bp = Boosters.plan(garage.boosters, null, 1, null)
            out += AdviceCard("boost", "🧪", "Reforços: ${garage.boosters.values.sum()} no estoque", bp.lines.take(3), 3, AdviceTarget.RACE)
        }

        // 4) Clube: quem excluir, rebaixar e promover pela regra.
        if (club.members.isNotEmpty()) {
            val r = ClubManager.evaluate(club, garage.playerName, now)
            val lines = buildList {
                if (r.kicks.isNotEmpty()) add("Excluir: " + r.kicks.take(4).joinToString { it.name } + if (r.kicks.size > 4) " +${r.kicks.size - 4}" else "")
                if (r.demotions.isNotEmpty()) add("Rebaixar: " + r.demotions.take(3).joinToString { it.name })
                if (r.promotions.isNotEmpty()) add("Promover: " + r.promotions.take(3).joinToString { it.name })
                add(r.whenToAct)
            }
            val urgent = (r.kicks + r.demotions + r.promotions).any { it.urgency >= 2 }
            out += AdviceCard("club", "👥", "Clube ${garage.clubName}".trim(), lines, if (urgent) 0 else 2, AdviceTarget.CLUB)
        } else {
            out += AdviceCard("club", "👥", "Clube: quem excluir e promover", emptyList(), 4, AdviceTarget.GAME, "Abra Clube › Membros no jogo e role a lista")
        }

        // 5) Última derrota analisada.
        races.filter { it.won == false && it.review.isNotBlank() }.maxByOrNull { it.timestamp }?.takeIf { now - it.timestamp < 24 * H }?.let { r ->
            out += AdviceCard("review", "🔎", "Por que você perdeu a última", r.review.lines().filter { it.isNotBlank() }.take(4), 2, AdviceTarget.RACE)
        }

        // 6) Rumo à Série 12.
        val prog = Progression.plan(garage, series, races, flags)
        prog.steps.firstOrNull()?.let { st ->
            out += AdviceCard("s12", "🏁", "Rumo ao Grand Slam de Ouro",
                listOfNotNull(
                    "Próxima: S${st.series.number} ${st.series.name}" + (st.flagsMissing?.let { f -> if (f > 0) " • faltam $f bandeiras" else " • bandeiras ok" } ?: ""),
                    st.scoreGap?.let { g -> if (g > 0) "Pontuação: faltam +$g para a faixa" else "Pontuação já entra na faixa" },
                    prog.advice.firstOrNull(),
                ), 3, AdviceTarget.EVOLVE)
        }

        // 7) Objetivos do jogo: um cartão só (antes eram 3–5 cartões "Diário: …" com o texto inteiro).
        val pend = objectives.filter { !it.done && (it.endsAt == null || it.endsAt > now) }
            .sortedWith(compareBy({ it.weekly }, { it.endsAt ?: Long.MAX_VALUE }))
        if (pend.isNotEmpty()) {
            val soon = pend.any { !it.weekly && it.endsAt != null && it.endsAt - now < 4 * H }
            out += AdviceCard("obj", "📋", "Objetivos: ${pend.size} pendentes",
                pend.take(3).map { o -> "${if (o.weekly) "Semana" else "Hoje"} • ${shortObjective(o.text)} ${o.progress}/${o.target}" },
                if (soon) 1 else 3, AdviceTarget.GAME)
        }

        // 8) Eventos com prazo (do resumo diário).
        DailyBriefing.build(garage, races, events, tyreObs, lastMetaTime, now, objectives, gp)
            .filter { it.icon !in setOf("✅", "🗓️") && (it.icon in setOf("⏱", "📋", "🎯", "🎁", "⭐") || it.priority == 0) }
            // o GP já tem cartão próprio acima
            .filterNot { out.any { c -> c.id == "gp" } && (it.icon == "🏆" || it.title.startsWith("Grand Prix")) }
            .take(3).forEachIndexed { i, b ->
                out += AdviceCard("brief$i", b.icon, b.title, listOf(b.detail).filter { it.isNotBlank() }, b.priority.coerceAtMost(3),
                    when (b.action) {
                        BriefingAction.GARAGE -> AdviceTarget.GARAGE
                        BriefingAction.RACE -> AdviceTarget.RACE
                        BriefingAction.EVOLVE -> AdviceTarget.EVOLVE
                        BriefingAction.AI -> AdviceTarget.AI
                        BriefingAction.OPEN_GAME -> AdviceTarget.GAME
                        BriefingAction.NONE -> AdviceTarget.NONE
                    })
            }

        return out.sortedWith(compareBy<AdviceCard> { it.missing != null }.thenBy { it.priority })
    }

    /** "Derrote os Rivais em Duelos SÉRIE 8 ou eventos do Grand Prix™" → "Derrote rivais (Duelos/GP)". */
    fun shortObjective(t: String): String {
        val c = Txt.compact(t)
        return when {
            Txt.has(c, "CLAIMREWARD") -> "Resgatar recompensa"
            Txt.has(c, "CLASSIFIQUE") -> "Terminar à frente do rival (Duelos/GP)"
            Txt.has(c, "DERROTE") -> "Vencer rivais (Duelos/GP)"
            Txt.has(c, "CONCLUA") && Txt.has(c, "EPICO") -> "Correr com piloto Épico+"
            Txt.has(c, "ABRA") && Txt.has(c, "CAIXOTE") -> "Abrir caixotes"
            Txt.has(c, "REFORCO") -> "Usar reforços"
            Txt.has(c, "ATUALIZE") || Txt.has(c, "EVOLUA") -> "Evoluir um piloto/peça"
            Txt.has(c, "ENTRENOJOGO") -> "Entrar no jogo"
            else -> t.replace(Regex("""\s+"""), " ").take(38)
        }
    }

    fun money(v: Long): String = when {
        v >= 1_000_000_000 -> "%.1f bi moedas".format(v / 1e9)
        v >= 1_000_000 -> "%.1f mi moedas".format(v / 1e6)
        v >= 1_000 -> "%.0f mil moedas".format(v / 1e3)
        else -> "$v moedas"
    }
}
