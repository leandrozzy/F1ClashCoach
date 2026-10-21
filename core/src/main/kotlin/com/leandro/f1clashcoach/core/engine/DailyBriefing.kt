package com.leandro.f1clashcoach.core.engine

import com.leandro.f1clashcoach.core.model.ActiveEvent
import com.leandro.f1clashcoach.core.model.Garage
import com.leandro.f1clashcoach.core.model.GameMode
import com.leandro.f1clashcoach.core.model.GrandPrixState
import com.leandro.f1clashcoach.core.model.Objective
import com.leandro.f1clashcoach.core.model.RaceRecord
import com.leandro.f1clashcoach.core.model.TyreObservation

enum class BriefingAction { OPEN_GAME, GARAGE, RACE, EVOLVE, AI, NONE }

data class BriefingItem(
    /** 0 = mais urgente. */
    val priority: Int,
    val icon: String,
    val title: String,
    val detail: String,
    val action: BriefingAction = BriefingAction.NONE,
    /** Pergunta pronta para a IA quando action = AI. */
    val aiPrompt: String? = null,
)

/**
 * "O que fazer agora", gerado automaticamente a partir dos seus dados — como a
 * caixa de tarefas do OSM. Não depende de internet; a IA complementa por cima.
 */
object DailyBriefing {

    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    fun build(
        garage: Garage,
        races: List<RaceRecord>,
        events: List<ActiveEvent>,
        tyreObs: List<TyreObservation>,
        lastMetaTime: Long,
        now: Long,
        objectives: List<Objective> = emptyList(),
        gp: GrandPrixState? = null,
    ): List<BriefingItem> {
        val items = mutableListOf<BriefingItem>()

        // 0) Grand Prix (o que mais rende moedas/cartas na semana)
        if (gp != null && (gp.roundEndsAt == null || gp.roundEndsAt > now)) {
            val left = gp.roundEndsAt?.let { formatLeft(it - now) }
            val played = if (gp.racesPlayed != null && gp.racesTotal != null) " • ${gp.racesPlayed}/${gp.racesTotal} corridas" else ""
            val detail = buildList {
                gp.recommendedScore?.let { rec ->
                    if (garage.teamScore in 1 until rec) add("Sua pontuação (${garage.teamScore}) está abaixo da recomendada ($rec) para ${gp.tier ?: "este nível"}: escolha um nível abaixo.")
                    else if (garage.teamScore >= rec) add("Pontuação ${garage.teamScore} ≥ $rec recomendada: nível adequado.")
                }
                gp.entryFee?.let { add("Entrada: ${it / 1000}k moedas (paga uma vez).") }
                add("Classificatória: termine entre os 70% melhores (até 4 corridas).")
            }.joinToString(" ")
            items += BriefingItem(
                if (gp.roundEndsAt != null && gp.roundEndsAt - now < 6 * HOUR) 0 else 1, "🏆",
                "${gp.name}: ${gp.round ?: "rodada"}" + (left?.let { " — faltam $it" } ?: "") + played,
                detail, BriefingAction.RACE,
            )
            val legendEquipped = garage.drivers.filter { it.equipped && it.legendary }
            if (gp.legendaryAllowed == false && legendEquipped.isNotEmpty()) {
                val sub = garage.drivers.filter { !it.equipped && !it.legendary }.maxByOrNull { it.stats.total * 10 + it.progress.level }
                items += BriefingItem(
                    0, "⛔", "Troque ${legendEquipped.joinToString { it.name }} antes do GP",
                    "Este Grand Prix não permite pilotos lendários." + (sub?.let { " Sugestão: ${it.name} (nível ${it.progress.level})." } ?: ""),
                    BriefingAction.GARAGE,
                )
            }
            val feat = (garage.drivers.filter { it.featured }.map { it.name } + garage.parts.filter { it.featured }.map { it.name }).distinct()
            if (feat.isNotEmpty() && gp.featuredBoostPct != null) {
                items += BriefingItem(
                    1, "⭐", "Use seus destaques (+${gp.featuredBoostPct}%) no GP",
                    "Você tem: ${feat.take(4).joinToString()}. Recursos em destaque ganham bônus nas estatísticas durante o evento.",
                    BriefingAction.GARAGE,
                )
            }
        }

        // 0b) Objetivos do jogo (diários e semanais)
        objectives.filter { !it.done && (it.endsAt == null || it.endsAt > now) }
            .sortedWith(compareBy({ it.weekly }, { it.endsAt ?: Long.MAX_VALUE }))
            .take(5)
            .forEach { o ->
                val left = o.endsAt?.let { formatLeft(it - now) }
                items += BriefingItem(
                    when { !o.weekly && o.endsAt != null && o.endsAt - now < 4 * HOUR -> 0; !o.weekly -> 1; else -> 3 },
                    if (o.weekly) "🗓️" else "✅",
                    "${if (o.weekly) "Semanal" else "Diário"}: ${o.text.take(70)} (${o.progress}/${o.target})",
                    objectiveHint(o.text) + (left?.let { " Faltam $it." } ?: ""),
                    BriefingAction.OPEN_GAME,
                )
            }

        // 1) Dados mínimos
        if (garage.parts.size < 7 || garage.drivers.size < 2) {
            items += BriefingItem(
                0, "📋", "Complete a leitura da garagem",
                "Tenho ${garage.parts.size} peças e ${garage.drivers.size} pilotos. Ligue o Coach e abra cada peça/piloto no jogo — sem isso setup e upgrades ficam imprecisos.",
                BriefingAction.GARAGE,
            )
        }
        if (garage.coins == 0L && garage.parts.isNotEmpty()) {
            items += BriefingItem(1, "🪙", "Informe suas moedas", "Toque nos números da Garagem para eu calcular o que dá para evoluir hoje.", BriefingAction.GARAGE)
        }

        // 2) Eventos com prazo
        events.filter { !it.isOver(now) }.sortedBy { it.endsAt ?: Long.MAX_VALUE }.forEach { e ->
            val left = e.remainingMs(now)
            val leftTxt = left?.let { formatLeft(it) }
            val urgent = left != null && left < DAY
            items += BriefingItem(
                if (urgent) 0 else 2,
                when (e.mode) { GameMode.GRAND_PRIX -> "🏆"; GameMode.F1_MOMENTS -> "🎬"; GameMode.EXHIBITION -> "🧪"; GameMode.LEAGUE -> "📈"; else -> "🎯" },
                "${e.mode.label}: ${e.name}" + (leftTxt?.let { " — faltam $it" } ?: ""),
                e.aiAdvice.takeIf { it.isNotBlank() }?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(160) ?: e.mode.tip,
                BriefingAction.AI,
                "Estou no ${e.mode.label} \"${e.name}\"" + (leftTxt?.let { " (faltam $it)" } ?: "") +
                    ". Pesquise como funciona e as recompensas, e me diga exatamente como jogar e o que priorizar com a minha garagem.",
            )
        }
        val sawGpThisWeek = events.any { it.mode == GameMode.GRAND_PRIX && now - it.lastSeen < 7 * DAY } ||
            (gp != null && now - gp.seenAt < 7 * DAY)
        if (!sawGpThisWeek) {
            items += BriefingItem(
                3, "🏆", "Confira o Grand Prix da semana",
                "Abra a tela do Grand Prix com o Coach ligado: eu leio a pista e o prazo e preparo setup e estratégia.",
                BriefingAction.OPEN_GAME,
            )
        }

        // 3) Forma recente (proteção contra "tilt")
        val recent = races.filter { it.won != null }.sortedByDescending { it.timestamp }
        val streak = recent.takeWhile { it.won == false }.size
        if (streak >= 3) {
            items += BriefingItem(
                0, "🛑", "Pare a liga: $streak derrotas seguidas",
                "Seguir correndo assim só perde troféus. Faça os upgrades indicados, revise o setup da próxima pista e volte depois.",
                BriefingAction.EVOLVE,
            )
        }
        val last20 = recent.take(20)
        if (last20.size >= 10) {
            val rate = last20.count { it.won == true }.toDouble() / last20.size
            if (rate >= 0.6) items += BriefingItem(2, "🚀", "Boa fase: ${(rate * 100).toInt()}% nas últimas ${last20.size}", "Aproveite para empurrar a liga e buscar a promoção de série hoje.", BriefingAction.OPEN_GAME)
        }

        // 4) Upgrades
        if (garage.parts.isNotEmpty() || garage.drivers.isNotEmpty()) {
            val focus = HistoryAnalyzer.analyze(races).byTrack.take(6).map { it.track }
            val plan = UpgradePlanner.plan(garage, focus)
            val ready = plan.ranked.filter { it.canUpgradeNow }
            if (ready.size > 3) {
                items += BriefingItem(
                    1, "⬆️", "${ready.size} itens com cartas para evoluir",
                    "Comece por: ${ready.take(4).joinToString { it.name }}. Peças equipadas primeiro — você tem ${garage.coins / 1_000_000}M moedas.",
                    BriefingAction.EVOLVE,
                )
            } else plan.doNow.take(3).forEach { a ->
                items += BriefingItem(1, "⬆️", "Evolua ${a.name}", "${a.kind} nível ${a.level} → ${a.level + 1}. ${a.reason}.", BriefingAction.EVOLVE)
            }
            plan.collectCards.firstOrNull()?.let { a ->
                items += BriefingItem(4, "🃏", "Junte cartas de ${a.name}", "${a.reason}. Peça no clube e confira a loja diária.", BriefingAction.EVOLVE)
            }
        }

        // 5) Pontos fracos
        val report = HistoryAnalyzer.analyze(races)
        report.worstTracks.firstOrNull { it.winRate < 0.4 }?.let { t ->
            items += BriefingItem(
                3, "🔧", "Ajuste para ${t.track.name} (${t.wins}/${t.races})",
                "Abra a aba Corrida nessa pista: setup e estratégia de pneus recomendados.", BriefingAction.RACE,
            )
        }
        report.insights.filter { it.contains("Priorize") }.take(1).forEach {
            items += BriefingItem(3, "💡", "Diagnóstico do seu histórico", it, BriefingAction.EVOLVE)
        }

        // 6) Meta e calibração
        if (now - lastMetaTime > 7 * DAY) {
            items += BriefingItem(
                2, "🌐", "Atualize o meta da semana",
                "Os Featured Assets e guias mudam toda semana. A IA pesquisa e cruza com a sua garagem.",
                BriefingAction.AI, "META",
            )
        }
        val calibratedTracks = tyreObs.map { it.trackId }.toSet()
        if (races.size >= 5 && calibratedTracks.size < 3) {
            items += BriefingItem(5, "🛞", "Corra com o Coach ligado", "Assim eu meço o desgaste real dos pneus e as estratégias ficam precisas (pistas calibradas: ${calibratedTracks.size}).", BriefingAction.OPEN_GAME)
        }
        if (races.isEmpty()) {
            items += BriefingItem(2, "🏁", "Faça uma corrida com o Coach ligado", "Ele registra o resultado sozinho e começa a aprender seu estilo.", BriefingAction.OPEN_GAME)
        }

        return items.distinctBy { it.title }.sortedBy { it.priority }
    }

    /** Como cumprir cada tipo de objetivo do jogo. */
    fun objectiveHint(text: String): String {
        val t = Txt.compact(text)
        return when {
            Txt.has(t, "GRANDPRIX") && (Txt.has(t, "DERROTE") || Txt.has(t, "RIVAIS")) -> "Vença duelos ou corridas do Grand Prix; use o setup e a estratégia da aba Corrida."
            Txt.has(t, "GRANDPRIX") -> "Entre no Grand Prix (aba Corrida do jogo) — conta também para o evento."
            Txt.has(t, "DUELOS") || Txt.has(t, "CORRIDAS") -> "Jogue Duelos da sua série; o Coach registra cada resultado."
            Txt.has(t, "REFORCOS") -> "Antes de largar, equipe um reforço (você tem vários no estoque)."
            Txt.has(t, "ATUALIZE") || Txt.has(t, "EVOLUA") || Txt.has(t, "UPGRADE") -> "Evolua um item com cartas prontas (veja a aba Evoluir)."
            Txt.has(t, "CAIXOTES") || Txt.has(t, "CAIXOTE") -> "Abra os caixotes da garagem/loja."
            Txt.has(t, "ANUNCIOS") -> "Loja → Ganhe recompensas diárias → Assistir."
            Txt.has(t, "ENTRENOJOGO") -> "Já conta ao abrir o jogo."
            else -> "Siga o objetivo no jogo."
        }
    }

    fun formatLeft(ms: Long): String {
        if (ms <= 0) return "encerrado"
        val d = ms / DAY
        val h = (ms % DAY) / HOUR
        val m = (ms % HOUR) / 60_000
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}min"
            else -> "${m}min"
        }
    }

    /** Texto compacto dos eventos para o contexto da IA. */
    fun eventsContext(events: List<ActiveEvent>, now: Long): String =
        events.filter { !it.isOver(now) }.joinToString("\n") { e ->
            "- ${e.mode.label}: ${e.name}" + (e.remainingMs(now)?.let { " (faltam ${formatLeft(it)})" } ?: "")
        }

}
