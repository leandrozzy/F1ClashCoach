package com.leandro.f1clashcoach.core.model

import kotlinx.serialization.Serializable

/** Modos de jogo do F1 Clash que o Coach acompanha. */
@Serializable
enum class GameMode(val label: String, val keywords: List<String>, val tip: String) {
    LEAGUE(
        "Liga / Série",
        listOf("LEAGUE", "LIGA", "DIVISION", "DIVISÃO", "PROMOTION", "PROMOÇÃO", "RELEGATION", "REBAIXAMENTO"),
        "Liga é onde sobe de série. Corra quando o carro estiver no nível da série; após 3 derrotas seguidas, pare, evolua e volte.",
    ),
    GRAND_PRIX(
        "Grand Prix semanal",
        listOf("GRAND PRIX", "GRANDE PRÊMIO", "GRANDE PREMIO", "GP WEEKEND", "FIM DE SEMANA"),
        "No Grand Prix a classificação e os boosters pesam muito. Use o setup da pista do GP e guarde boosters para a corrida final.",
    ),
    EVENT(
        "Evento",
        listOf("EVENT", "EVENTO", "LIMITED TIME", "TEMPO LIMITADO", "MILESTONE", "MARCO", "CHALLENGE", "DESAFIO"),
        "Eventos dão cartas e recompensas exclusivas. Complete os marcos que dão cartas das suas peças prioritárias antes do prazo.",
    ),
    EXHIBITION(
        "Exibição",
        listOf("EXHIBITION", "EXIBIÇÃO", "EXIBICAO", "FRIENDLY", "AMISTOSO"),
        "Exibição não vale troféu: use para testar setups e estratégias de pneu sem risco.",
    ),
    F1_MOMENTS(
        "Momentos F1",
        listOf("F1 MOMENTS", "MOMENTS", "MOMENTOS F1", "MOMENTOS", "SCENARIO", "CENÁRIO"),
        "Momentos F1 recriam situações reais com objetivo específico: leia a meta antes de largar e planeje pneu/energia só para ela.",
    ),
    CLUB(
        "Clube",
        listOf("CLUB", "CLUBE", "TEAM CHAT", "DONATE", "DOAR", "REQUEST CARDS", "PEDIR CARTAS"),
        "No clube peça cartas da peça que está mais perto de evoluir e doe as que você não usa.",
    ),
    SEASON(
        "Temporada / Passe",
        listOf("SEASON PASS", "PASSE", "SEASON SCORE", "TEMPORADA", "TIERS", "NÍVEIS DO PASSE"),
        "Resgate as recompensas do passe da temporada; o Season Score cresce com upgrades dos pilotos desta temporada.",
    );

    companion object {
        fun detect(upperText: String): GameMode? =
            entries.map { m -> m to m.keywords.count { upperText.contains(it) } }
                .filter { it.second > 0 }
                .maxByOrNull { it.second }?.first
    }
}

/** Evento/modo visto na tela, com prazo e conselho. */
@Serializable
data class ActiveEvent(
    val id: String,
    val mode: GameMode,
    val name: String,
    /** Quando termina (epoch ms), se o timer foi lido. */
    val endsAt: Long? = null,
    val firstSeen: Long = 0,
    val lastSeen: Long = 0,
    val aiAdvice: String = "",
    val aiAdviceAt: Long = 0,
) {
    fun remainingMs(now: Long): Long? = endsAt?.let { it - now }
    fun isOver(now: Long): Boolean = endsAt?.let { it < now } ?: (now - lastSeen > 8L * 24 * 3600 * 1000)
}
