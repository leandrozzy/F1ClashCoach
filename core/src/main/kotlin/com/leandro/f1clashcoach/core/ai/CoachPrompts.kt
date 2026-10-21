package com.leandro.f1clashcoach.core.ai

import com.leandro.f1clashcoach.core.engine.DailyBriefing
import com.leandro.f1clashcoach.core.engine.HistoryAnalyzer
import com.leandro.f1clashcoach.core.engine.SetupOptimizer
import com.leandro.f1clashcoach.core.engine.UpgradePlanner
import com.leandro.f1clashcoach.core.model.ActiveEvent
import com.leandro.f1clashcoach.core.model.Garage
import com.leandro.f1clashcoach.core.model.GrandPrixState
import com.leandro.f1clashcoach.core.model.Objective
import com.leandro.f1clashcoach.core.model.RaceRecord
import com.leandro.f1clashcoach.core.model.Track
import com.leandro.f1clashcoach.core.model.TrackCatalog
import java.time.LocalDate

/** Prompts e contexto enviados à IA. */
object CoachPrompts {

    fun system(today: LocalDate = LocalDate.now()): String = """
Você é o F1 Clash Coach, um treinador especialista no jogo mobile F1 Clash (Hutch Games).
Responda sempre em português do Brasil, de forma direta e prática — o jogador lê no celular.
Hoje é $today.

Seu objetivo: fazer o jogador subir de série, ganhar mais corridas e evoluir a conta gastando bem moedas, bucks e cartas.

Como trabalhar:
- Use os dados da garagem e do histórico que vêm no contexto. Não invente números que não estão lá; se faltar dado, diga qual print ou informação precisa.
- Quando houver busca na web, procure o meta atual (guias da série, Featured Assets da semana, eventos, patch notes da Hutch) e diga a data/fonte do que encontrou.
- Ao analisar um print, primeiro descreva em 1 linha a tela que você vê, depois dê a recomendação.
- Dê respostas em passos numerados ou listas curtas. Destaque a ação principal no começo.
- Separe "faça agora" de "planeje para depois".
- O app só aconselha: nunca sugira automação, bots, auto-clicker ou qualquer coisa que viole os termos do jogo.

Modos do jogo que você deve conhecer e orientar: Liga/Série (sobe de série), Grand Prix semanal (classificação + corridas, boosters), Eventos com marcos e prazo, Exibição (sem troféus, para testar), Momentos F1 (cenários com objetivo), Clube (pedir/doar cartas) e Temporada/Passe.

Regras oficiais (central de ajuda da Hutch) que você deve usar:
- Duelos: 12 Séries de 4 pistas (as pistas giram). Bandeiras desbloqueiam séries; podem ser perdidas em derrotas, mas série desbloqueada não se perde. Séries 10/11/12 = Grand Slam de Bronze/Prata/Ouro.
- Troféus (Pit Pass): na série mais alta desbloqueada, vitória = 3, derrota = 1, volta mais rápida = +1.
- Matchmaking pela Pontuação da Equipe; cada série tem faixa recomendada (ex.: S8 850–1100, S12 ~1450–1799).
- Grand Prix: 5 dias, Classificatória (qua/qui/sex, basta passar em 1 dia), Abertura (sáb) e Final (dom); prêmios só para quem chega à Final. Níveis: Júnior (peças/pilotos até Série 3), Competidor (até 6), Contendor (até 9), Campeão (sem limite). Lendários só quando o evento permite.
- Recursos em Destaque: +10% nas estatísticas em todo o jogo enquanto o evento está ativo.
- Liga semanal: segunda a segunda, posição pelas moedas ganhas em vitórias; zonas de promoção/rebaixamento.
- Clubes: Reputação (estrelas) vem de Duelos e das recompensas finais de GP; Exibições (~4 semanas) definem promoção/rebaixamento de divisão; perks melhores por divisão. Power-up Reputation Rise dobra reputação de Duelos; Flag Storm dobra bandeiras; Pit Pass Premium +5% reputação.
- Overtake Mode vem da Bateria, liberado após a 1ª volta; bloqueado em Safety Car, com 0% de pneu ou entrando no pit.

Objetivo do jogador: chegar à Série 12 (Grand Slam de Ouro), vencer muitos Duelos para gerar estrelas e subir o clube (ele é presidente) e montar os melhores pilotos (inclusive lendários e edições especiais) e peças para isso.

Mecânicas que você deve considerar: atributos das peças (Speed, Cornering, Power Unit, Qualifying, tempo de Pit Stop, Overtake Mode), atributos dos pilotos (Overtaking, Defending, Qualifying, Race Start, Tyre Management), pneus (macio, médio, duro, intermediário, chuva), ritmo (conservar/neutro/atacar), energia da bateria, clima, Featured Assets, boosters, ligas, eventos e economia (moedas, bucks, caixas, cartas).
""".trim()

    /** Resumo compacto da conta para colocar no prompt. */
    fun accountContext(
        garage: Garage,
        history: List<RaceRecord>,
        focusTrack: Track? = null,
        events: List<ActiveEvent> = emptyList(),
        now: Long = System.currentTimeMillis(),
        objectives: List<Objective> = emptyList(),
        gp: GrandPrixState? = null,
        series: List<com.leandro.f1clashcoach.core.model.SeriesInfo> = emptyList(),
        flags: Int? = null,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("## Conta")
        sb.appendLine("Série: ${garage.series} | Pontuação da equipe: ${garage.teamScore} | Moedas: ${garage.coins} | Notas (bucks): ${garage.bucks}" +
            (garage.clubName.takeIf { it.isNotBlank() }?.let { " | Clube: $it" } ?: ""))
        garage.carTotals?.let { t ->
            sb.appendLine("Carro equipado (totais): Velocidade ${t.speed}, Curvas ${t.cornering}, Unidade de Potência ${t.powerUnit}, Qualificação ${t.qualifying}, Pit stop ${t.pitStop}s, Modo de Ultrapassagem ${t.overtakeMode}")
        }
        if (garage.featuredAssets.isNotEmpty()) sb.appendLine("Recursos em destaque da semana: ${garage.featuredAssets.joinToString()}")
        if (garage.boosters.isNotEmpty()) {
            sb.appendLine("Reforços no estoque (1 por piloto na largada + 1 a cada pit; Largada só vale na 1ª volta):")
            com.leandro.f1clashcoach.core.engine.Boosters.owned(garage.boosters).sortedByDescending { it.count }.forEach { b ->
                sb.appendLine("- ${b.name} x${b.count}: " + (b.def?.effects?.entries?.joinToString { "${it.key.label} +${it.value}" } ?: "bônus não catalogado"))
            }
        }
        gp?.let { g ->
            sb.appendLine("## Grand Prix atual: ${g.name} | ${g.round ?: ""} | termina em ${g.roundEndsAt?.let { DailyBriefing.formatLeft(it - now) } ?: "?"} | " +
                "pontuação recomendada ${g.recommendedScore ?: "?"} | entrada ${g.entryFee ?: "?"} | lendários ${when (g.legendaryAllowed) { true -> "permitidos"; false -> "PROIBIDOS"; null -> "?" }} | " +
                "corridas ${g.racesPlayed ?: "?"}/${g.racesTotal ?: "?"} | meus pontos ${g.myPoints ?: "?"} | bônus destaque ${g.featuredBoostPct ?: "?"}%")
        }
        if (series.isNotEmpty()) {
            val plan = com.leandro.f1clashcoach.core.engine.Progression.plan(garage, series, history, flags)
            sb.appendLine("## Rumo à Série 12 (Grand Slam de Ouro) — bandeiras: ${flags ?: "?"}")
            plan.steps.forEach { st -> sb.appendLine("- Série ${st.series.number} ${st.series.name}: faixa ${st.series.scoreMin}–${st.series.scoreMax}, faltam ${st.flagsMissing ?: "?"} bandeiras, prêmio ${st.series.prizeCoins} moedas ★${st.series.prizeStars}") }
            plan.advice.take(3).forEach { sb.appendLine("* $it") }
        }
        val pend = objectives.filter { !it.done && (it.endsAt == null || it.endsAt > now) }
        if (pend.isNotEmpty()) {
            sb.appendLine("## Objetivos pendentes no jogo")
            pend.forEach { sb.appendLine("- ${if (it.weekly) "Semanal" else "Diário"}: ${it.text} (${it.progress}/${it.target})") }
        }
        sb.appendLine("## Peças (slot | nome | raridade | nível | cartas | custo | Speed/Cornering/PU/Quali/Pit/OVT | equipada) — atributos 0 = não lidos")
        garage.parts.sortedBy { it.slot.ordinal }.forEach { p ->
            val s = p.stats
            sb.appendLine("${p.slot.label} | ${p.name} | ${p.rarity.label} | ${p.progress.level} | ${p.progress.cardsOwned}/${p.progress.cardsNeeded} | ${p.progress.upgradeCoins} | ${s.speed}/${s.cornering}/${s.powerUnit}/${s.qualifying}/${s.pitStop}/${s.overtakeMode} | ${if (p.equipped) "sim" else "não"}")
        }
        sb.appendLine("## Pilotos (nome | raridade | nível | cartas | custo | OVT/DEF/QUALI/LARG/PNEU | escalado) — ⭐ destaque. Raridades: Comum/Rara/Épica, Lendária, Especial (edição)")
        garage.drivers.sortedByDescending { it.equipped }.forEach { d ->
            val s = d.stats
            sb.appendLine("${d.name}${if (d.featured) " ⭐" else ""} | ${d.rarity.label}${if (d.edition.isNotBlank()) " (${d.edition})" else ""} | ${d.progress.level} | ${d.progress.cardsOwned}/${d.progress.cardsNeeded} | ${d.progress.upgradeCoins} | ${s.overtaking}/${s.defending}/${s.qualifying}/${s.raceStart}/${s.tyreManagement} | ${if (d.equipped) "sim" else "não"}")
        }
        val missing = com.leandro.f1clashcoach.core.model.Catalog.driverCollection(garage).filter { !it.owned }.sortedByDescending { it.maxTotal }.take(8)
        if (missing.isNotEmpty()) sb.appendLine("## Pilotos mais fortes que ainda NÃO tenho (catálogo 2026, total no nível máximo): " + missing.joinToString { "${it.name} ${it.rarity.label} (série ${it.series}, máx ${it.maxTotal})" })
        val missingParts = com.leandro.f1clashcoach.core.model.Catalog.partCollection(garage).filter { !it.owned }.sortedByDescending { it.maxTotal }.take(8)
        if (missingParts.isNotEmpty()) sb.appendLine("## Peças mais fortes que ainda NÃO tenho: " + missingParts.joinToString { "${it.name} ${it.slot?.label} ${it.rarity.label} (série ${it.series}, máx ${it.maxTotal})" })
        val report = HistoryAnalyzer.analyze(history)
        sb.appendLine("## Histórico")
        sb.appendLine("Corridas: ${report.races} | Vitórias: ${report.wins} | Taxa: ${(report.winRate * 100).toInt()}% | Últimas 20: ${(report.recentWinRate * 100).toInt()}%")
        report.byTrack.take(8).forEach { sb.appendLine("- ${it.track.name}: ${it.wins}/${it.races}") }
        report.insights.forEach { sb.appendLine("* $it") }
        history.sortedByDescending { it.timestamp }.take(5).forEach { r ->
            sb.appendLine("Recente: ${TrackCatalog.byId(r.trackId)?.name ?: r.trackId} | grid ${r.gridPositions} → ${r.finishPositions} | ${when (r.won) { true -> "vitória"; false -> "derrota"; null -> "?" }} | ${r.strategy}")
        }
        if (garage.parts.isNotEmpty() || garage.drivers.isNotEmpty()) {
            val plan = UpgradePlanner.plan(garage)
            sb.appendLine("## Upgrades calculados pelo app")
            plan.doNow.take(5).forEach { sb.appendLine("- Agora: ${it.name} (${it.kind}) — ${it.reason}") }
            plan.collectCards.take(3).forEach { sb.appendLine("- Juntar cartas: ${it.name} — ${it.reason}") }
        }
        val ev = DailyBriefing.eventsContext(events, now)
        if (ev.isNotBlank()) {
            sb.appendLine("## Eventos e modos vistos na tela (ativos)")
            sb.appendLine(ev)
        }
        if (focusTrack != null) {
            val setup = SetupOptimizer.optimize(garage, focusTrack)
            sb.appendLine("## Setup calculado para ${focusTrack.name} (${focusTrack.type.label})")
            setup.choices.forEach { c -> sb.appendLine("- ${c.slot.label}: ${c.best?.name ?: "—"}") }
            sb.appendLine("- Pilotos: ${setup.drivers.joinToString { it.name }}")
        }
        return sb.toString()
    }

    fun withContext(question: String, context: String): String =
        "<contexto_da_conta>\n$context</contexto_da_conta>\n\n$question"

    const val ANALYZE_SCREENSHOT = "Analise este print do F1 Clash. Diga qual tela é, extraia os números relevantes e me diga exatamente o que fazer agora para melhorar meu resultado ou minha conta."

    const val WEEKLY_META = "Pesquise na web o meta atual do F1 Clash para a minha série: melhores peças e pilotos, Featured Assets desta semana, estratégias de pneus das pistas da série e eventos ativos. Depois cruze com a minha garagem e diga o que eu devo usar, evoluir e jogar esta semana."

    const val EVOLUTION_PLAN = "Monte um plano de evolução de 7 dias para a minha conta: o que evoluir primeiro com as moedas que tenho, quais cartas buscar, quais eventos priorizar, e como gastar bucks com eficiência. Use o histórico para apontar meu maior ponto fraco."

    /** Gerado automaticamente 1x por dia (ou quando aparece evento novo), sem o usuário pedir. */
    const val DAILY_BRIEFING = "Monte meu BRIEFING DO DIA no F1 Clash, como um treinador. Pesquise na web o que está ativo agora (Grand Prix da semana, eventos, Momentos F1, Featured Assets, novidades da temporada) e cruze com a minha conta. Formato: 1) As 3 ações mais importantes de hoje, em ordem. 2) Eventos/GP ativos: como jogar cada um e se vale a pena para mim. 3) Upgrade do dia. 4) Um alerta sobre algo que eu esteja fazendo errado (pelo histórico). Seja curto e direto."

    const val TARGET_META = "Pesquise na web (guias atuais da Série 12, Séries 10-11, Featured Assets da semana) e monte meu caminho até a Série 12 (Grand Slam de Ouro): 1) melhores pilotos para a Série 12, separando comuns/raros/épicos, LENDÁRIOS e EDIÇÕES ESPECIAIS, e quais eu já tenho; 2) melhores peças por slot para a Série 12 e alternativas que eu tenho; 3) ordem exata de upgrades com minhas moedas e cartas para subir a pontuação da equipe até ~1450; 4) onde buscar as cartas que faltam (caixotes, eventos, loja, clube). Seja específico com nomes."

    const val CLUB_PLAN = "Sou presidente do clube. Monte um plano para subir o clube na liga de clubes (Exibição): como maximizar estrelas/reputação (Duelos, GP, Reputation Rise, Pit Pass Premium), metas por membro por semana, como organizar pedidos e doações de cartas, critérios para recrutar/remover membros inativos e mensagens prontas para o chat do clube."

    fun eventAnalysis(modeLabel: String, name: String): String =
        "Esta é a tela do $modeLabel \"$name\" no F1 Clash. Leia regras, objetivo, pista, prazo e recompensas que aparecem no print, pesquise na web se precisar, e diga em até 8 linhas: vale a pena? como jogar (setup, pneus, energia, boosters)? o que fazer primeiro?"

    fun racePlan(track: Track, laps: Int, extra: String): String =
        "Vou correr em ${track.name} (${track.type.label}), $laps voltas. $extra Valide a estratégia calculada pelo app e diga ritmo, uso de energia e quando parar."
}
