package com.leandro.f1clashcoach.core.engine

import com.leandro.f1clashcoach.core.model.CarStats
import com.leandro.f1clashcoach.core.model.DriverStats
import com.leandro.f1clashcoach.core.model.GameMode
import com.leandro.f1clashcoach.core.model.PartSlot
import com.leandro.f1clashcoach.core.model.Track
import com.leandro.f1clashcoach.core.model.TrackCatalog
import com.leandro.f1clashcoach.core.model.Tyre
import com.leandro.f1clashcoach.core.model.Weather

/** Linha de texto reconhecida pelo OCR, com caixa normalizada (0..1). */
data class TextLine(val text: String, val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f) {
    val cx: Float get() = (left + right) / 2
    val cy: Float get() = (top + bottom) / 2
}

enum class GameScreen(val label: String) {
    RACE("Corrida"), QUALIFYING("Classificação"), PRE_RACE("Pré-corrida"), RACE_RESULT("Resultado"),
    PARTS("Peças"), DRIVERS("Pilotos"), UPGRADE("Upgrade"), STORE("Loja"), EVENT("Evento / modo de jogo"), MENU("Menu"),
    HOME("Início"), RACE_MENU("Escolha de corrida"), GP("Grand Prix"), OBJECTIVES("Objetivos"), TEAM_DRIVERS("Equipe • Pilotos"),
    TEAM_CARS("Equipe • Carros"), BOOSTERS("Reforços"), CLUB("Clube"), PIT_PASS("Pit Pass"), UNKNOWN("Desconhecida")
}

data class ParsedScreen(
    val screen: GameScreen,
    val track: Track? = null,
    val series: Int? = null,
    val lap: Int? = null,
    val totalLaps: Int? = null,
    val positions: List<Int> = emptyList(),
    val percents: List<Int> = emptyList(),
    val weather: Weather? = null,
    val rainExpected: Boolean = false,
    val tyres: List<Tyre> = emptyList(),
    val won: Boolean? = null,
    val level: Int? = null,
    val cards: Pair<Int, Int>? = null,
    val slot: PartSlot? = null,
    val carStats: CarStats? = null,
    val driverStats: DriverStats? = null,
    val itemName: String? = null,
    val coins: Long? = null,
    /** Modo de jogo identificado (liga, GP, evento, exibição, momentos…). */
    val mode: GameMode? = null,
    /** Título principal da tela (nome do evento/GP). */
    val title: String? = null,
    /** Tempo restante lido de um timer ("2d 4h", "3h 20m"), em ms. */
    val timeLeftMs: Long? = null,
    /** Dados das telas de menu reais (Equipe, Objetivos, GP, Loja, Clube…). */
    val game: GameRead? = null,
    val rawText: String = "",
) {
    fun toSnapshot(): RaceSnapshot {
        val ds = (0 until maxOf(1, minOf(2, maxOf(positions.size, tyres.size, percents.size)))).map { i ->
            DriverLive(position = positions.getOrNull(i), tyre = tyres.getOrNull(i), tyreLife = percents.getOrNull(i))
        }
        return RaceSnapshot(lap, totalLaps, weather ?: Weather.DRY, rainExpected, energy = null, drivers = ds)
    }
}

/**
 * Classifica a tela do F1 Clash e extrai os números visíveis.
 * Aceita textos em inglês e português (o jogo pode estar em qualquer idioma).
 */
object ScreenReader {

    private val SCREEN_KEYWORDS: Map<GameScreen, List<String>> = mapOf(
        GameScreen.RACE to listOf("LAP", "VOLTA", "PIT", "BOX", "PUSH", "CONSERVE", "ATTACK", "OVERTAKE", "DEPLOY", "HARVEST"),
        GameScreen.QUALIFYING to listOf("QUALIFYING", "CLASSIFICAÇÃO", "CLASSIFICACAO", "Q1", "Q2", "Q3", "POLE"),
        GameScreen.PRE_RACE to listOf("SETUP", "STRATEGY", "ESTRATÉGIA", "ESTRATEGIA", "START TYRE", "PNEU INICIAL", "FORECAST", "PREVISÃO"),
        GameScreen.RACE_RESULT to listOf("RESULTS", "RESULTADO", "VICTORY", "VITÓRIA", "VITORIA", "DEFEAT", "DERROTA", "WINNER", "TROPHIES", "TROFÉUS"),
        GameScreen.PARTS to listOf("BRAKES", "GEARBOX", "REAR WING", "FRONT WING", "SUSPENSION", "ENGINE", "BATTERY", "FREIOS", "MOTOR", "BATERIA", "CORNERING", "POWER UNIT"),
        GameScreen.DRIVERS to listOf("OVERTAKING", "DEFENDING", "TYRE MANAGEMENT", "RACE START", "ULTRAPASSAGEM", "DEFESA", "GESTÃO DE PNEUS", "LARGADA"),
        GameScreen.UPGRADE to listOf("UPGRADE", "MELHORAR", "EVOLUIR", "LEVEL UP", "SUBIR DE NÍVEL"),
        GameScreen.STORE to listOf("STORE", "SHOP", "LOJA", "CRATE", "CAIXA", "OFFER", "OFERTA", "BUCKS"),
        GameScreen.EVENT to listOf(
            "EVENT", "EVENTO", "GRAND PRIX", "GRANDE PRÊMIO", "REWARDS", "RECOMPENSAS", "EXHIBITION", "EXIBIÇÃO",
            "F1 MOMENTS", "MOMENTOS", "ENDS IN", "TERMINA EM", "MILESTONE", "MARCO", "OBJECTIVE", "OBJETIVO",
        ),
        GameScreen.MENU to listOf("GARAGE", "GARAGEM", "LEAGUE", "LIGA", "CLUB", "CLUBE", "RACE NOW", "CORRER"),
    )

    private val LAP_RE = Regex("""(?:LAP|VOLTA)\s*(\d{1,2})\s*/\s*(\d{1,2})""")
    private val LAP_ALT_RE = Regex("""\b(\d{1,2})\s*/\s*(\d{1,2})\s*(?:LAPS|VOLTAS)\b""")
    private val POS_RE = Regex("""\bP\s?(\d{1,2})\b""")
    private val PCT_RE = Regex("""\b(\d{1,3})\s?%""")
    private val SERIES_RE = Regex("""(?:SERIES|SÉRIE|SERIE)\s*(\d{1,2})""")
    private val LEVEL_RE = Regex("""(?:LEVEL|LVL|NÍVEL|NIVEL|NV\.?)\s*(\d{1,2})""")
    private val CARDS_RE = Regex("""\b(\d{1,4})\s*/\s*(\d{1,4})\b""")
    private val COINS_RE = Regex("""(?:COINS|MOEDAS)\s*[:\s]\s*([\d.,]+)""")

    private fun statRe(vararg names: String) = Regex("""(?:${names.joinToString("|") { Regex.escape(it) }})\s*[:\s]\s*(\d{1,3}(?:[.,]\d{1,2})?)""")
    private val SPEED_RE = statRe("SPEED", "VELOCIDADE")
    private val CORNER_RE = statRe("CORNERING", "CURVAS", "CURVA")
    private val PU_RE = statRe("POWER UNIT", "UNIDADE DE POTÊNCIA", "UNIDADE DE POTENCIA", "POTÊNCIA", "POTENCIA")
    private val QUALI_RE = statRe("QUALIFYING", "CLASSIFICAÇÃO", "CLASSIFICACAO")
    private val PIT_RE = statRe("PIT STOP TIME", "PIT STOP", "TEMPO DE PIT STOP", "PIT")
    private val OVT_MODE_RE = statRe("OVERTAKE MODE", "MODO ULTRAPASSAGEM", "DRS")
    private val OVERTAKING_RE = statRe("OVERTAKING", "ULTRAPASSAGEM")
    private val DEFENDING_RE = statRe("DEFENDING", "DEFESA")
    private val START_RE = statRe("RACE START", "LARGADA")
    private val TM_RE = statRe("TYRE MANAGEMENT", "TIRE MANAGEMENT", "GESTÃO DE PNEUS", "GESTAO DE PNEUS", "PNEUS")

    fun classify(lines: List<TextLine>): GameScreen {
        GameReader.classify(lines.joinToString(" ") { Txt.compact(it.text) })?.let { return it }
        val text = lines.joinToString("\n") { it.text.uppercase() }
        if (LAP_RE.containsMatchIn(text) || LAP_ALT_RE.containsMatchIn(text)) return GameScreen.RACE
        val scores = SCREEN_KEYWORDS.mapValues { (_, kws) -> kws.count { text.contains(it) } }
        val best = scores.maxByOrNull { it.value } ?: return GameScreen.UNKNOWN
        return if (best.value >= 2 || (best.value == 1 && best.key == GameScreen.RACE_RESULT)) best.key else GameScreen.UNKNOWN
    }

    fun parse(lines: List<TextLine>, lastSlot: PartSlot? = null): ParsedScreen {
        val sorted = lines.sortedWith(compareBy({ it.top }, { it.left }))
        val text = sorted.joinToString("\n") { it.text.uppercase() }
        val screen = classify(sorted)

        val lap = LAP_RE.find(text) ?: LAP_ALT_RE.find(text)
        val lapN = lap?.groupValues?.get(1)?.toIntOrNull()
        val lapTotal = lap?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it in 1..80 }

        val positions = POS_RE.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it in 1..22 }.distinct().take(4).toList()
        // Percentuais em ordem da esquerda para a direita (piloto 1, piloto 2).
        val percents = sorted.sortedBy { it.left }
            .flatMap { l -> PCT_RE.findAll(l.text).mapNotNull { it.groupValues[1].toIntOrNull() }.toList() }
            .filter { it in 0..100 }.take(4)

        val weather = when {
            Regex("""HEAVY RAIN|CHUVA FORTE|STORM|TEMPESTADE""").containsMatchIn(text) -> Weather.HEAVY_RAIN
            Regex("""LIGHT RAIN|CHUVA LEVE|\bRAIN\b|\bCHUVA\b|DRIZZLE|GAROA""").containsMatchIn(text) -> Weather.LIGHT_RAIN
            Regex("""\bDRY\b|\bSECO\b|SUNNY|ENSOLARADO""").containsMatchIn(text) -> Weather.DRY
            else -> null
        }
        val rainExpected = Regex("""RAIN (?:EXPECTED|IN|SOON)|CHUVA (?:PREVISTA|EM|CHEGANDO)|FORECAST.*RAIN|PREVISÃO.*CHUVA""").containsMatchIn(text)

        val tyres = Regex("""\b(SOFT|MEDIUM|HARD|INTER(?:MEDIATE)?|WET|MACIO|MÉDIO|MEDIO|DURO|INTERMEDIÁRIO|CHUVA EXTREMA)\b""")
            .findAll(text).mapNotNull { tyreOf(it.value) }.take(4).toList()

        val won = when {
            Regex("""VICTORY|VITÓRIA|VITORIA|YOU WIN|VOCÊ VENCEU|WINNER""").containsMatchIn(text) -> true
            Regex("""DEFEAT|DERROTA|YOU LOSE|VOCÊ PERDEU""").containsMatchIn(text) -> false
            else -> null
        }

        fun num(re: Regex): Double? = re.find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()

        val carStats = if (screen == GameScreen.PARTS || screen == GameScreen.UPGRADE) {
            val sp = num(SPEED_RE); val co = num(CORNER_RE); val pu = num(PU_RE)
            if (sp != null || co != null || pu != null) CarStats(
                speed = sp?.toInt() ?: 0, cornering = co?.toInt() ?: 0, powerUnit = pu?.toInt() ?: 0,
                qualifying = num(QUALI_RE)?.toInt() ?: 0, pitStop = num(PIT_RE)?.takeIf { it < 10 } ?: 0.0,
                overtakeMode = num(OVT_MODE_RE)?.toInt() ?: 0,
            ) else null
        } else null

        val driverStats = if (screen == GameScreen.DRIVERS || (screen == GameScreen.UPGRADE && carStats == null)) {
            val ov = num(OVERTAKING_RE); val de = num(DEFENDING_RE)
            if (ov != null || de != null) DriverStats(
                overtaking = ov?.toInt() ?: 0, defending = de?.toInt() ?: 0, qualifying = num(QUALI_RE)?.toInt() ?: 0,
                raceStart = num(START_RE)?.toInt() ?: 0, tyreManagement = num(TM_RE)?.toInt() ?: 0,
            ) else null
        } else null

        val slot = PartSlot.entries.firstOrNull { s -> s.keywords.any { text.contains(it) } }
            ?.takeIf { carStats != null || screen == GameScreen.PARTS }

        val cards = if (screen in setOf(GameScreen.PARTS, GameScreen.DRIVERS, GameScreen.UPGRADE)) {
            CARDS_RE.findAll(text).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
                .firstOrNull { (a, b) -> b in 2..9999 && a <= b * 3 }
        } else null

        // Nome do item: a maior linha "de título" no topo da tela que não seja um rótulo conhecido.
        val itemName = if (carStats != null || driverStats != null) {
            sorted.filter { it.top < 0.35f }
                .map { it.text.trim() }
                .filter { t -> t.length in 3..28 && t.none { it.isDigit() } && !isLabel(t.uppercase()) }
                .maxByOrNull { it.length }
        } else null

        val mode = GameMode.detect(text)
        val title = sorted.filter { it.top < 0.25f }
            .map { it.text.trim() }
            .filter { t -> t.length in 4..40 && t.count { it.isLetter() } >= 4 && !TIMER_RE.containsMatchIn(t.uppercase()) }
            .maxByOrNull { it.length }

        return ParsedScreen(
            screen = screen,
            track = TrackCatalog.match(text),
            series = SERIES_RE.find(text)?.groupValues?.get(1)?.toIntOrNull(),
            lap = lapN, totalLaps = lapTotal,
            positions = positions, percents = percents,
            weather = weather, rainExpected = rainExpected, tyres = tyres, won = won,
            level = LEVEL_RE.find(text)?.groupValues?.get(1)?.toIntOrNull(),
            cards = cards, slot = slot, carStats = carStats, driverStats = driverStats, itemName = itemName,
            coins = COINS_RE.find(text)?.groupValues?.get(1)?.replace(".", "")?.replace(",", "")?.toLongOrNull(),
            mode = mode, title = title, timeLeftMs = parseDuration(text),
            game = if (screen in GAME_SCREENS) GameReader.read(lines, screen, lastSlot) else null,
            rawText = text,
        )
    }

    private val GAME_SCREENS = setOf(
        GameScreen.HOME, GameScreen.RACE_MENU, GameScreen.GP, GameScreen.OBJECTIVES, GameScreen.TEAM_DRIVERS,
        GameScreen.TEAM_CARS, GameScreen.BOOSTERS, GameScreen.CLUB, GameScreen.PIT_PASS, GameScreen.STORE,
    )

    private val TIMER_RE = Regex("""\b(\d{1,3})\s*(D|DIAS?|DAYS?|H|HRS?|HORAS?|M|MIN|MINS?)\b""")

    /** Lê o timer da tela ("2D 4H", "Termina em 13d15h06m") e devolve em ms. */
    fun parseDuration(upperText: String): Long? = Txt.duration(upperText)

    private fun isLabel(t: String): Boolean =
        SCREEN_KEYWORDS.values.flatten().any { t.contains(it) } ||
            listOf("SPEED", "VELOCIDADE", "QUALIFYING", "UPGRADE", "COMMON", "RARE", "EPIC", "LEGENDARY", "COMUM", "RARA", "ÉPICA", "LENDÁRIA").any { t.contains(it) }

    fun tyreOf(raw: String): Tyre? = when (raw.uppercase()) {
        "SOFT", "MACIO" -> Tyre.SOFT
        "MEDIUM", "MÉDIO", "MEDIO" -> Tyre.MEDIUM
        "HARD", "DURO" -> Tyre.HARD
        "INTER", "INTERMEDIATE", "INTERMEDIÁRIO" -> Tyre.INTER
        "WET", "CHUVA EXTREMA" -> Tyre.WET
        else -> null
    }
}
