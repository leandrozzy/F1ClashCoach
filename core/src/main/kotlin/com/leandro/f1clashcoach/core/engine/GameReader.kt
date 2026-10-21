package com.leandro.f1clashcoach.core.engine

import com.leandro.f1clashcoach.core.model.CarStats
import com.leandro.f1clashcoach.core.model.DriverStats
import com.leandro.f1clashcoach.core.model.PartSlot
import com.leandro.f1clashcoach.core.model.Weather
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Utilidades de texto tolerantes a erros de OCR (acentos, espaços colados, letras trocadas). */
object Txt {
    private val MARKS = Regex("\\p{M}+")

    fun fold(s: String): String = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").uppercase()

    /** Só letras e dígitos, sem acento, maiúsculo: "Início de Corrida" → "INICIODECORRIDA". */
    fun compact(s: String): String = fold(s).filter { it.isLetterOrDigit() }

    /** Procura [kw] (já compacto) em [line] (compacto), aceitando ~1 letra trocada a cada 5. */
    fun has(line: String, kw: String): Boolean {
        if (line.contains(kw)) return true
        if (kw.length < 5 || kw.length > line.length) return false
        val maxErr = max(1, kw.length / 5)
        for (i in 0..(line.length - kw.length)) {
            var err = 0
            var j = 0
            while (j < kw.length && err <= maxErr) {
                if (line[i + j] != kw[j]) err++
                j++
            }
            if (err <= maxErr) return true
        }
        return false
    }

    /** Distância de edição com corte antecipado. */
    fun lev(a: String, b: String, limit: Int = Int.MAX_VALUE): Int {
        if (abs(a.length - b.length) > limit) return limit + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                rowMin = min(rowMin, cur[j])
            }
            if (rowMin > limit) return limit + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Corrige letras confundidas com dígitos em contexto numérico (o→0, l→1…). */
    fun digitsFix(s: String): String = s.map {
        when (it) { 'o', 'O' -> '0'; 'l', 'I', 'i', '|' -> '1'; 'S' -> '5'; 'B' -> '8'; else -> it }
    }.joinToString("")

    /** Inteiro com separadores de milhar ("46,672,703" / "46.672.703" / "★25.243"). */
    fun bigNumber(s: String): Long? {
        val m = Regex("""(\d{1,3}(?:[.,]\d{3})+|\d+)""").find(s.replace(" ", "")) ?: return null
        return m.value.replace(".", "").replace(",", "").toLongOrNull()
    }

    private val TIMER = Regex("""(\d{1,3})\s*([DHMS])(?![A-Z])""")
    private val MARKERS = listOf("TERMINA", "TERMINZ", "RESTAURA", "ENDSIN", "ENDS IN", "TERMINAEM", "RODADATERMINA", "OFERTATERMINA")

    /** "13d15h06m56s" / "14h 55m" / "4d 16h" → ms. Prefere o timer logo após "Termina em". */
    fun duration(raw: String): Long? {
        val up = fold(raw)
        val compactUp = up.replace(" ", "")
        val start = MARKERS.map { compactUp.indexOf(it.replace(" ", "")) }.filter { it >= 0 }.minOrNull()
        val zone = if (start != null) compactUp.substring(start, min(compactUp.length, start + 40)) else up
        val ms = TIMER.findAll(zone).toList()
        if (ms.isEmpty()) return null
        var total = 0L
        var lastEnd = -1
        var units = 0
        for (m in ms) {
            if (lastEnd >= 0 && m.range.first - lastEnd > 1) break
            val n = m.groupValues[1].toLong()
            total += when (m.groupValues[2]) {
                "D" -> n * 86_400_000L
                "H" -> n * 3_600_000L
                "M" -> n * 60_000L
                else -> n * 1000L
            }
            lastEnd = m.range.last + 1
            units++
        }
        // Sem marcador, só aceita timers compostos ("4d16h") ou com dia/hora, para não confundir "300 M" (milhões).
        if (start == null && units < 2 && !ms.first().groupValues[2].let { it == "D" || it == "H" }) return null
        return total.takeIf { it > 0 }
    }
}

/** Card da grade de peças/pilotos (aba Equipe). */
data class GridTile(
    val name: String,
    val level: Int?,
    val cardsOwned: Int,
    val cardsNeeded: Int,
    val slot: PartSlot?,
    val featured: Boolean,
    /** Canto superior esquerdo do nome (fração da tela): o app amostra a cor da carta a partir daqui. */
    val nameLeft: Float = 0f,
    val nameTop: Float = 0f,
    /** Raridade pela cor da carta (preenchida pelo app, que tem a imagem). */
    val rarity: com.leandro.f1clashcoach.core.model.Rarity = com.leandro.f1clashcoach.core.model.Rarity.UNKNOWN,
) {
    val canUpgrade: Boolean get() = cardsNeeded > 0 && cardsOwned >= cardsNeeded
}

/** Tela de detalhe de um piloto ou peça. */
data class AssetDetail(
    val name: String,
    val isDriver: Boolean,
    val rarity: com.leandro.f1clashcoach.core.model.Rarity,
    val minTier: String?,
    val cardsOwned: Int?,
    val cardsNeeded: Int?,
    val level: Int?,
    val upgradeCost: Long?,
    val driverStats: DriverStats? = null,
    val driverGain: DriverStats? = null,
    val carStats: CarStats? = null,
    val carGain: CarStats? = null,
)

data class ObjectiveRead(val weekly: Boolean, val text: String, val progress: Int, val target: Int, val reward: Long?)

data class GpRead(
    val name: String,
    val round: String? = null,
    val timeLeftMs: Long? = null,
    val recommendedScore: Int? = null,
    val entryFee: Long? = null,
    val legendaryAllowed: Boolean? = null,
    val tier: String? = null,
    val racesPlayed: Int? = null,
    val racesTotal: Int? = null,
    val featuredBoostPct: Int? = null,
    val leaderboard: List<Pair<String, Int>> = emptyList(),
    /** Pista do GP, quando reconhecida pelo nome. */
    val trackId: String? = null,
    val laps: Int? = null,
    val rainChance: Int? = null,
    val sponsorTask: String? = null,
    val racePrize: Long? = null,
)

/** Tudo que dá para extrair das telas de menu do F1 Clash (layout vertical, PT-BR ou EN). */
data class ClubMemberRead(val name: String, val role: String, val rep: Long)

data class GameRead(
    val screen: GameScreen? = null,
    val coins: Long? = null,
    val bucks: Long? = null,
    val teamScore: Int? = null,
    val carTotals: CarStats? = null,
    /** Pilotos escalados (nome + atributos da tabela comparativa). */
    val driverPair: List<Pair<String?, DriverStats>> = emptyList(),
    val tiles: List<GridTile> = emptyList(),
    /** Peças equipadas no topo da aba Carros, na ordem do jogo. */
    val equippedParts: List<GridTile> = emptyList(),
    val objectives: List<ObjectiveRead> = emptyList(),
    val objectivesDailyLeftMs: Long? = null,
    val objectivesWeeklyLeftMs: Long? = null,
    val gp: GpRead? = null,
    val featured: List<String> = emptyList(),
    val boosters: Map<String, Int> = emptyMap(),
    val seasonName: String? = null,
    val seasonLeftMs: Long? = null,
    val clubName: String? = null,
    val clubRep: Long? = null,
    val clubLeagueName: String? = null,
    val clubLeagueLeftMs: Long? = null,
    val freeCrateReady: Boolean = false,
    /** Tempo até o próximo caixote grátis (loja). */
    val freeCrateLeftMs: Long? = null,
    /** Membros lidos na aba Membros do clube. */
    val clubMembers: List<ClubMemberRead> = emptyList(),
    val clubMemberCount: Int? = null,
    /** Texto de regras/descrição do clube, quando aparece na tela. */
    val clubRules: String? = null,
    val series: Int? = null,
    /** Loadout limitado de GP: não representa a equipe principal. */
    val restricted: Boolean = false,
    val asset: AssetDetail? = null,
    /** Cartões de séries de Duelos visíveis. */
    val seriesCards: List<com.leandro.f1clashcoach.core.model.SeriesInfo> = emptyList(),
    /** Total de bandeiras (cabeçalho da seleção de séries). */
    val flags: Int? = null,
    /** Tempos de volta por composto na largada: piloto → (pneu → segundos). */
    val tyreLapTimes: Map<String, Map<com.leandro.f1clashcoach.core.model.Tyre, Double>> = emptyMap(),
    val tyreLapRanges: Map<com.leandro.f1clashcoach.core.model.Tyre, IntRange> = emptyMap(),
    val totalLaps: Int? = null,
    val race: RaceHud? = null,
    val duel: DuelResult? = null,
    /** Pontuação da equipe do rival na tela de pareamento. */
    val rivalTeamScore: Int? = null,
)

object GameReader {

    private class L(val t: TextLine) {
        val c = Txt.compact(t.text)
        val cx get() = t.cx
        val cy get() = t.cy
        val h get() = max(0.008f, t.bottom - t.top)
    }

    private fun sameRow(a: L, b: L) = abs(a.cy - b.cy) < max(0.012f, max(a.h, b.h) * 0.75f)

    private val CARDS = Regex("""^(?:(\d{1,2})\s*[|I]?\s+)?(\d{1,5})\s*/\s*(\d{1,5})$""")
    private val DRIVER_NAME = Regex("""^[A-ZÀ-Ý]{1,2}\.\s?[A-Za-zÀ-ÿ'’.\- ]{2,}$""")

    /** Ordem dos slots no topo da aba Carros. */
    val EQUIPPED_ORDER = listOf(
        PartSlot.FRONT_WING, PartSlot.BRAKES, PartSlot.SUSPENSION, PartSlot.REAR_WING,
        PartSlot.GEARBOX, PartSlot.ENGINE, PartSlot.BATTERY,
    )

    private val SLOT_HEADERS = mapOf(
        PartSlot.BRAKES to listOf("FREIOS", "BRAKES"),
        PartSlot.GEARBOX to listOf("CAIXADECAMBIO", "GEARBOX"),
        PartSlot.REAR_WING to listOf("ASATRASEIRA", "REARWING"),
        PartSlot.FRONT_WING to listOf("ASADIANTEIRA", "FRONTWING"),
        PartSlot.SUSPENSION to listOf("SUSPENSAO", "SUSPENSION"),
        PartSlot.ENGINE to listOf("MOTOR", "ENGINE"),
        PartSlot.BATTERY to listOf("BATERIA", "BATTERY"),
    )

    /** Classifica pelas palavras-chave das telas reais. Retorna null se não reconhecer. */
    fun classify(all: String): GameScreen? {
        fun h(vararg kws: String) = kws.count { Txt.has(all, it) }
        val tabs = h("PILOTOS", "CARROS", "REFORCOS")
        return when {
            h("VENCEDOR") >= 1 || (h("RESULTADOS") >= 1 && Regex("""\d{1,3}PTS""").containsMatchIn(all)) -> GameScreen.RACE_RESULT
            (h("LIDERANDO", "TOQUEPARAMUDAR", "PITSTOP", "TROCANDOPNEUS") >= 2 ||
                (h("LIDERANDO") >= 1 && (Regex("""\bFIM\b""").containsMatchIn(all) || all.contains("KMH")))) && Regex("""VOLT""").containsMatchIn(all) -> GameScreen.RACE
            h("ESTATQUALIFICACAO") >= 1 && h("TEMPO") >= 1 -> GameScreen.QUALIFYING
            h("PROCURANDOPORRIVAL") >= 1 || Regex("""PONTUACAODAEQUIPE.*PONTUACAODAEQUIPE""").containsMatchIn(all) && h("VITORIA") == 0 && h("CONFIGURACOESDEEQUIPE") == 0 -> GameScreen.PRE_RACE
            h("OSRECURSOSEMDESTAQUERECEBEM", "RECEBEMUMAUMENTO") >= 1 -> GameScreen.FEATURED
            h("DISPONIVELEM") >= 1 && h("ESTATNONIVELMAX", "ESTATISTICASDOPILOTOATUAL", "ESTATISTICASDAPECAATUAL", "COLETEMAIS", "ATRIBUIRAOCARRO") >= 1 &&
                h("CONFIGURACOESDEEQUIPE") == 0 -> GameScreen.ASSET_DETAIL
            h("SELECIONEUMCOMPOSTO", "COMPOSTODEPNEU") >= 1 || (h("ADERENCIA") >= 2 && h("SOFT", "MEDIUM", "HARD") >= 2) -> GameScreen.PRE_RACE_TYRES
            h("AMAISREQUERIDOS", "REQUERIDOSPARADESBLOQUEAR") >= 1 || (h("PONTUACAODAEQUIPE") >= 1 && h("BANDEIRAS") >= 1 && h("VITORIA", "DERROTA") >= 1) -> GameScreen.DUEL_SERIES
            h("OBJETIVOSDIARIOS", "OBJETIVOSSEMANAIS", "LOJADOSOBJETIVOS", "DAILYOBJECTIVES") >= 1 -> GameScreen.OBJECTIVES
            h("DUELOS") >= 1 && h("MOMENTOSF1", "DESAFIOSINDIVIDUAIS") >= 1 -> GameScreen.RACE_MENU
            h("JOGARCORRIDA", "ZONADEPROMOCAO") >= 1 && h("GRANDPRIX") >= 1 -> GameScreen.GP
            h("JOGARCORRIDA") >= 1 && h("TAREFADOPATROCINADOR", "GANHOSPORCORRIDA", "VOLTAS") >= 2 -> GameScreen.GP
            h("JUNIOR", "COMPETIDOR", "CONTENDOR", "CAMPEAO") >= 3 -> GameScreen.GP
            h("RODADACLASSIFICATORIA", "PREMIOS", "TAREFA") >= 2 && h("GRANDPRIX") >= 1 -> GameScreen.GP
            h("REFORCOSPODEMSEREQUIPADOS", "BOOSTERSCANBEEQUIPPED") >= 1 -> GameScreen.BOOSTERS
            h("CAIXOTES", "MOEDAS", "NOTAS") >= 3 && h("VELOCIDADE", "ULTRAPASSANDO") == 0 -> GameScreen.STORE
            h("VELOCIDADE", "TEMPOMEDIODEPITSTOP", "UNIDADEDEPOTENCIA", "MODODEULTRAPASSAGEM") >= 2 -> GameScreen.TEAM_CARS
            tabs >= 3 && SLOT_HEADERS.values.flatten().count { Txt.has(all, it) } >= 1 &&
                h("ULTRAPASSANDO", "GERENCIAMENTODEPNEUS") == 0 -> GameScreen.TEAM_CARS
            h("ULTRAPASSANDO", "GERENCIAMENTODEPNEUS", "INICIODECORRIDA", "PILOTOSLENDARIOS", "EDICOESESPECIAIS") >= 1 &&
                h("PILOTOS", "CARROS") >= 1 -> GameScreen.TEAM_DRIVERS
            tabs >= 3 -> GameScreen.TEAM_DRIVERS
            h("BATEPAPO", "MEMBROS", "REPUTACAO") >= 2 -> GameScreen.CLUB
            h("CAIXOTES", "MOEDAS", "NOTAS", "OFERTA", "MERCADODERECURSOS") >= 3 -> GameScreen.STORE
            Regex("""EXIBICAO\d{1,3}""").containsMatchIn(all) && h("PREMIUM", "ATIVAR") >= 1 -> GameScreen.PIT_PASS
            h("PITPASS") >= 1 && h("CORRIDA") >= 1 -> GameScreen.HOME
            else -> null
        }
    }

    fun read(lines: List<TextLine>, screen: GameScreen, lastSlot: PartSlot? = null): GameRead {
        val ls = lines.map { L(it) }.sortedWith(compareBy({ it.t.top }, { it.t.left }))
        val all = ls.joinToString(" ") { it.c }

        // ---- Barra superior: moedas e notas (bucks) ----
        val top = ls.filter { it.t.bottom < 0.04f && Regex("""\d""").containsMatchIn(it.t.text) }.sortedBy { it.t.left }
        val coins = top.getOrNull(0)?.let { Txt.bigNumber(it.t.text) }?.takeIf { it >= 1000 }
        val bucks = top.getOrNull(1)?.let { Txt.bigNumber(it.t.text) }

        var r = GameRead(screen = screen, coins = coins, bucks = bucks, restricted = Txt.has(all, "LIMITACAODERECURSOS") || Txt.has(all, "RECURSOSAPLICADA"))
        r = r.copy(series = Regex("""SERIE(\d{1,2})""").findAll(all).map { it.groupValues[1].toInt() }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key?.takeIf { screen == GameScreen.OBJECTIVES || screen == GameScreen.PIT_PASS })

        when (screen) {
            GameScreen.TEAM_CARS, GameScreen.TEAM_DRIVERS -> {
                r = r.copy(teamScore = teamScore(ls))
                if (screen == GameScreen.TEAM_CARS) r = readCars(ls, r, lastSlot) else r = readDrivers(ls, r)
            }
            GameScreen.OBJECTIVES -> r = readObjectives(ls, r)
            GameScreen.GP, GameScreen.RACE_MENU -> r = r.copy(gp = readGp(ls, all))
            GameScreen.STORE -> r = r.copy(featured = featured(ls), freeCrateLeftMs = ls.firstOrNull { Txt.has(it.c.replace('Z', 'A'), "CAIXOTEGRATIS") }
                ?.let { lbl -> ls.filter { it.cy > lbl.cy && it.cy - lbl.cy < 0.04f }.firstNotNullOfOrNull { Txt.duration(it.t.text) } })
            GameScreen.BOOSTERS -> r = r.copy(boosters = boosters(ls))
            GameScreen.CLUB -> r = readClub(ls, all, r)
            GameScreen.RACE -> r = r.copy(race = readRace(ls))
            GameScreen.RACE_RESULT -> r = r.copy(duel = readDuel(ls, all))
            GameScreen.PRE_RACE -> r = r.copy(
                teamScore = null,
                rivalTeamScore = ls.filter { it.cx > 0.5f && Regex("""^\d{3,4}$""").matches(it.t.text.trim()) && it.t.top in 0.5f..0.62f }.firstNotNullOfOrNull { it.t.text.trim().toIntOrNull() },
                flags = ls.firstOrNull { it.cx < 0.5f && it.t.top in 0.6f..0.66f && Regex("""^\d[.,]?\d{3}$""").matches(it.t.text.trim()) }?.let { Txt.bigNumber(it.t.text)?.toInt() },
            )
            GameScreen.ASSET_DETAIL -> r = r.copy(asset = readAsset(ls, all))
            GameScreen.FEATURED -> r = r.copy(featured = featuredInfo(ls))
            GameScreen.DUEL_SERIES -> r = readSeries(ls, r)
            GameScreen.PRE_RACE_TYRES -> r = readTyres(ls, all, r)
            GameScreen.PIT_PASS -> {
                val title = ls.firstOrNull { Regex("""EXIBICAO\d""").containsMatchIn(it.c) }
                r = r.copy(seasonName = title?.t?.text?.trim(), seasonLeftMs = Txt.duration(ls.joinToString(" ") { it.t.text }))
            }
            GameScreen.HOME -> {
                val pass = ls.firstOrNull { Regex("""EXIBIC\w{0,2}\d{1,3}""").containsMatchIn(it.c) }
                val passTimer = ls.firstOrNull { it.t.top < 0.1f && Txt.duration(it.t.text) != null }
                r = r.copy(
                    seasonName = pass?.t?.text?.trim(),
                    seasonLeftMs = passTimer?.let { Txt.duration(it.t.text) },
                    freeCrateReady = ls.any { Txt.has(it.c, "GRATIS") && it.t.top < 0.15f },
                )
            }
            else -> Unit
        }
        return r
    }

    private fun teamScore(ls: List<L>): Int? {
        val label = ls.firstOrNull { Txt.has(it.c, "PONTUACAODAEQUIPE") || Txt.has(it.c, "TEAMSCORE") } ?: return null
        return ls.filter { it !== label && it.cy > label.cy && it.cy - label.cy < 0.05f && abs(it.cx - label.cx) < 0.2f }
            .mapNotNull { Regex("""(\d{3,5})""").find(Txt.digitsFix(it.t.text.replace(Regex("^[A-Za-z]"), "")))?.value?.toIntOrNull() }
            .firstOrNull()
    }

    /** Valor numérico na mesma linha, à direita (true) ou à esquerda (false) do rótulo. */
    private fun valueNear(ls: List<L>, label: L, right: Boolean): Double? =
        ls.filter { it !== label && sameRow(it, label) && (if (right) it.t.left > label.t.left else it.t.right < label.t.left + 0.02f) }
            .sortedBy { abs(it.cx - label.cx) }
            .firstNotNullOfOrNull { l ->
                val s = Txt.digitsFix(l.t.text).replace(',', '.').replace(" ", "")
                Regex("""^(\d{1,4}(?:\.\d{1,2})?)S?$""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)?.toDoubleOrNull()
            }

    private fun labelLine(ls: List<L>, vararg kws: String): L? = ls.firstOrNull { l -> kws.any { Txt.has(l.c, it) } }

    private fun readCars(ls: List<L>, base: GameRead, lastSlot: PartSlot?): GameRead {
        val speed = labelLine(ls, "VELOCIDADE", "SPEED")
        val totals = if (speed != null) {
            fun v(vararg k: String) = labelLine(ls, *k)?.let { valueNear(ls, it, true) }
            val quali = ls.firstOrNull { (Txt.has(it.c, "QUALIFICACAO") || Txt.has(it.c, "QUALIFYING")) && abs(it.cy - (labelLine(ls, "CURVAS", "CORNERING")?.cy ?: it.cy)) < 0.02f }
            CarStats(
                speed = valueNear(ls, speed, true)?.toInt() ?: 0,
                cornering = v("CURVAS", "CORNERING")?.toInt() ?: 0,
                powerUnit = v("UNIDADEDEPOTENCIA", "POWERUNIT")?.toInt() ?: 0,
                qualifying = quali?.let { valueNear(ls, it, true) }?.toInt() ?: 0,
                pitStop = v("TEMPOMEDIODEPITSTOP", "PITSTOPTIME")?.takeIf { it < 30 } ?: 0.0,
                overtakeMode = v("MODODEULTRAPASSAGEM", "OVERTAKEMODE")?.toInt() ?: 0,
            )
        } else null

        val tiles = tiles(ls, isPart = true, lastSlot = lastSlot)
        // Peças acima dos totais = equipadas (grade de 4 + 3 no topo).
        val equipped = if (speed != null) {
            val scoreLabel = labelLine(ls, "PONTUACAODAEQUIPE")
            tiles.filter { t -> tileY(ls, t) in (scoreLabel?.cy ?: 0f)..speed.cy }
        } else emptyList()
        val equippedWithSlots = if (equipped.size == 7) equipped.mapIndexed { i, t -> t.copy(slot = EQUIPPED_ORDER[i]) } else equipped
        val gridTiles = tiles.filter { t -> equipped.none { it.name == t.name } }
        return base.copy(carTotals = totals, tiles = gridTiles, equippedParts = equippedWithSlots)
    }

    private fun tileY(ls: List<L>, t: GridTile): Float = ls.firstOrNull { it.t.text.trim() == t.name }?.cy ?: -1f

    private fun readDrivers(ls: List<L>, base: GameRead): GameRead {
        val labels = listOf(
            listOf("ULTRAPASSANDO", "OVERTAKING"), listOf("DEFESA", "DEFENDING"), listOf("QUALIFICACAO", "QUALIFYING"),
            listOf("INICIODECORRIDA", "RACESTART"), listOf("GERENCIAMENTODEPNEUS", "TYREMANAGEMENT"),
        )
        val rows = labels.map { kws -> labelLine(ls, *kws.toTypedArray()) }
        var pair = emptyList<Pair<String?, DriverStats>>()
        if (rows.count { it != null } >= 3) {
            fun col(right: Boolean) = rows.map { l -> l?.let { valueNear(ls, it, right)?.toInt() } ?: 0 }
            val a = col(false); val b = col(true)
            val firstRow = rows.filterNotNull().minOf { it.cy }
            // Os nomes ficam logo acima da tabela.
            val names = ls.filter { DRIVER_NAME.matches(it.t.text.trim()) && it.cy < firstRow && firstRow - it.cy < 0.12f }.sortedBy { it.cx }
            val n1 = names.firstOrNull { it.cx < 0.5f }?.t?.text?.trim()
            val n2 = names.lastOrNull { it.cx >= 0.5f }?.t?.text?.trim()
            pair = listOf(n1 to DriverStats(a[0], a[1], a[2], a[3], a[4]), n2 to DriverStats(b[0], b[1], b[2], b[3], b[4]))
        }
        return base.copy(driverPair = pair, tiles = tiles(ls, isPart = false, lastSlot = null))
    }

    /** Pareia nomes com a linha "nível cartas/necessárias" logo abaixo. */
    private fun tiles(ls: List<L>, isPart: Boolean, lastSlot: PartSlot?): List<GridTile> {
        val headers = ls.mapNotNull { l -> SLOT_HEADERS.entries.firstOrNull { (_, kws) -> kws.any { l.c == it || (it.length > 5 && Txt.has(l.c, it) && l.c.length <= it.length + 3) } }?.let { l to it.key } }
        val cardLines = ls.mapNotNull { l -> CARDS.find(Txt.digitsFix(l.t.text.trim()))?.let { l to it } }
        val result = mutableListOf<Pair<L, GridTile>>()
        for ((cl, m) in cardLines) {
            val name = ls.filter { n ->
                n !== cl && n.cy < cl.cy && cl.cy - n.cy < 0.035f && abs(n.cx - cl.cx) < 0.13f &&
                    n.t.text.count { it.isLetter() } >= 3 && CARDS.find(n.t.text.trim()) == null &&
                    !Txt.has(n.c, "ESTOQUE") && !Txt.has(n.c, "SERIE")
            }.minByOrNull { cl.cy - it.cy } ?: continue
            if (!isPart && !DRIVER_NAME.matches(name.t.text.trim())) continue
            val owned = m.groupValues[2].toInt(); val need = m.groupValues[3].toInt()
            if (need <= 0 || need > 5000) continue
            // Nível: no próprio texto ("7 751/400") ou um número curto à esquerda na mesma linha.
            val level = m.groupValues[1].toIntOrNull() ?: ls.firstOrNull { o ->
                o !== cl && sameRow(o, cl) && o.t.right <= cl.t.left + 0.01f && cl.t.left - o.t.right < 0.08f &&
                    Regex("""^\d{1,2}$""").matches(Txt.digitsFix(o.t.text.trim()))
            }?.let { Txt.digitsFix(it.t.text.trim()).toInt() }
            val slot = if (isPart) headers.filter { it.first.cy < name.cy }.maxByOrNull { it.first.cy }?.second ?: lastSlot else null
            // Selo "+10%" fica no canto superior do mesmo card.
            val featured = ls.any { f ->
                Regex("""\d{1,2}\s?%""").containsMatchIn(f.t.text) && f.cx >= name.t.left - 0.01f && f.cx <= name.t.left + 0.22f &&
                    f.cy <= name.cy + 0.005f && name.cy - f.cy < 0.04f
            }
            result += name to GridTile(name.t.text.trim(), level, owned, need, slot, featured, name.t.left, name.t.top)
        }
        // Ordem de leitura: linha a linha (tolerando pequenas diferenças de altura), da esquerda para a direita.
        return result.sortedWith(compareBy({ (it.first.cy / 0.03f).toInt() }, { it.first.cx })).map { it.second }.distinctBy { it.name + "@" + (it.nameTop / 0.03f).toInt() + "," + (it.nameLeft / 0.1f).toInt() }
    }

    private fun readObjectives(ls: List<L>, base: GameRead): GameRead {
        val daily = labelLine(ls, "OBJETIVOSDIARIOS", "DAILYOBJECTIVES")
        val weekly = labelLine(ls, "OBJETIVOSSEMANAIS", "WEEKLYOBJECTIVES")
        fun headerTimer(h: L?) = h?.let { hl -> ls.filter { sameRow(it, hl) }.firstNotNullOfOrNull { Txt.duration(it.t.text) } }
        val progress = Regex("""^(\d{1,3})\s*/\s*(\d{1,3})$""")
        val out = mutableListOf<ObjectiveRead>()
        var prevBottom = listOfNotNull(daily, weekly).minOfOrNull { it.cy } ?: 0.12f
        val sorted = ls.sortedBy { it.cy }
        for (p in sorted) {
            val m = progress.find(Txt.digitsFix(p.t.text.trim())) ?: continue
            if (p.cx > 0.4f || p.cy < 0.12f) continue
            val textLines = sorted.filter { it.cy > prevBottom + 0.003f && it.cy < p.cy && it.cx < 0.6f && it.t.text.count { c -> c.isLetter() } >= 2 &&
                !(weekly != null && it.cy < weekly.cy && p.cy > weekly.cy) &&
                it !== daily && it !== weekly && !Txt.has(it.c, "TERMINA") }
            prevBottom = p.cy
            val text = textLines.joinToString(" ") { it.t.text.trim() }.trim()
            if (text.isBlank()) continue
            val reward = sorted.firstOrNull { it !== p && abs(it.cy - p.cy) < 0.02f && it.cx > 0.45f && it.cx < 0.8f }?.let { Txt.bigNumber(Txt.digitsFix(it.t.text)) }
            val isWeekly = weekly != null && p.cy > weekly.cy
            out += ObjectiveRead(isWeekly, text, m.groupValues[1].toInt(), m.groupValues[2].toInt(), reward)
            if (weekly != null && textLines.any { it.cy < weekly.cy } ) Unit
        }
        return base.copy(objectives = out, objectivesDailyLeftMs = headerTimer(daily), objectivesWeeklyLeftMs = headerTimer(weekly))
    }

    private fun readGp(ls: List<L>, all: String): GpRead? {
        val gpIdx = ls.indexOfFirst { Txt.has(it.c, "GRANDPRIX") && it.t.top < 0.75f }
            .let { i -> if (i >= 0) i else ls.indexOfFirst { it.c.startsWith("GRAND") && it.t.top < 0.2f } }
        if (gpIdx < 0) return null
        val gpLine = ls[gpIdx]
        // Nome: linhas logo acima de "GRAND PRIX" (ex.: "UNITED STATES"), ignorando "FORMULA 1".
        val nameParts = ls.filter { it.cy <= gpLine.cy && gpLine.cy - it.cy < 0.03f && abs(it.cx - gpLine.cx) < 0.25f && !Txt.has(it.c, "FORMULA") }
            .sortedBy { it.cy }.map { it.t.text.trim() }
        val name = Txt.fold(nameParts.joinToString(" ")).replace(Regex("""\s+"""), " ").replace("GRANDPRIX", "GRAND PRIX").trim()
            .let { n -> if (n.contains("GRAND PRIX")) n else "$n GRAND PRIX" }
            .split(" ").joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
        val round = when {
            Txt.has(all, "RODADACLASSIFICATORIA") || Txt.has(all, "CLASSIFICATORIA") -> "Rodada classificatória"
            Txt.has(all, "ABERTURA") && !Txt.has(all, "CLASSIFICATORIA") -> "Abertura"
            Txt.has(all, "RODADAFINAL") -> "Final"
            else -> null
        }
        val timerLine = ls.firstOrNull { Txt.has(it.c, "TERMINA") || Txt.has(it.c, "RODADATERMINA") }
        val timeLeft = timerLine?.let { tl -> Txt.duration(ls.filter { sameRow(it, tl) }.sortedBy { it.t.left }.joinToString(" ") { it.t.text }) }
        val rec = Regex("""(?:SUPERIOR[AZ]?|ABOVE)([0-9OIL]{3,5})""").find(all)?.groupValues?.get(1)?.let { Txt.digitsFix(it).toIntOrNull() }
        val fee = labelLine(ls, "TAXADEENTRADA", "ENTRYFEE")?.let { l -> ls.filter { sameRow(it, l) && it !== l }.firstNotNullOfOrNull { Txt.bigNumber(it.t.text) } }
            ?: labelLine(ls, "ENTRADA")?.let { l -> ls.filter { sameRow(it, l) && it !== l }.firstNotNullOfOrNull { Txt.bigNumber(it.t.text) } }
        val legendary = labelLine(ls, "PILOTOSLENDARIOS")?.let { l ->
            val v = ls.filter { sameRow(it, l) && it !== l }.joinToString(" ") { it.c }
            when { Txt.has(v, "NAOPERMITIDO") || v.contains("NAO") -> false; Txt.has(v, "PERMITIDO") || Txt.has(v, "DISPONIVEL") -> true; else -> null }
        }
        val tier = listOf("JUNIOR" to "Júnior", "COMPETIDOR" to "Competidor", "CONTENDOR" to "Contendor", "CAMPEAO" to "Campeão")
            .filter { (k, _) -> Txt.has(all, k) }.let { if (it.size == 1) it.first().second else null }
        val play = Regex("""(?:JOGARCORRIDA|PLAYRACE)([0-9OIL])([0-9OIL])""").find(all)
            ?.let { m -> Regex("""(\d)(\d)""").find(Txt.digitsFix(m.groupValues[1] + m.groupValues[2])) }
        val boost = if (Txt.has(all, "RECURSOSEMDESTAQUE")) Regex("""(\d{1,2})%""").findAll(ls.joinToString(" ") { it.t.text }).map { it.groupValues[1].toInt() }.firstOrNull { it in 5..50 } else null
        val pts = Regex("""^([0-9OoIl]{1,4})\s?PTS$""", RegexOption.IGNORE_CASE)
        val board = ls.mapNotNull { p -> pts.find(p.t.text.trim())?.let { p to (Txt.digitsFix(it.groupValues[1]).toIntOrNull() ?: 0) } }
            .mapNotNull { (p, v) ->
                ls.filter { it !== p && sameRow(it, p) && it.cx in 0.15f..0.75f && it.t.text.any { c -> c.isLetter() } }
                    .minByOrNull { abs(it.cy - p.cy) }?.let { it.t.text.trim() to v }
            }
        val track = com.leandro.f1clashcoach.core.model.TrackCatalog.tracks.firstOrNull { t ->
            t.aliases.any { a -> Txt.compact(a).length >= 4 && Txt.compact(name).contains(Txt.compact(a)) }
        }
        val prettyName = track?.let { "GP ${it.name} (${it.country})" } ?: name
        val voltas = labelLine(ls, "VOLTAS")
        val laps = voltas?.let { v -> Regex("""(\d{1,2})""").find(Txt.digitsFix(v.t.text.substringAfter(":", "")))?.value?.toIntOrNull() }
        val rain = voltas?.let { v -> ls.firstOrNull { it !== v && it.cy > v.cy && it.cy - v.cy < 0.05f && abs(it.cx - v.cx) < 0.15f && it.t.text.contains("%") } }
            ?.let { Regex("""(\d{1,3})\s?%""").find(Txt.digitsFix(it.t.text.replace("%", "%")))?.groupValues?.get(1)?.toIntOrNull() }
        val sponsor = labelLine(ls, "TAREFADOPATROCINADOR")?.let { l -> ls.firstOrNull { it.cy > l.cy && it.cy - l.cy < 0.03f && it !== l }?.t?.text?.trim() }
        val prize = labelLine(ls, "GANHOSPORCORRIDA")?.let { l -> ls.filter { sameRow(it, l) && it !== l }.firstNotNullOfOrNull { Txt.bigNumber(it.t.text) } }
        return GpRead(prettyName, round, timeLeft, rec, fee, legendary, tier, play?.groupValues?.get(1)?.toInt(), play?.groupValues?.get(2)?.toInt(), boost, board, track?.id, laps, rain, sponsor, prize)
    }

    private fun featured(ls: List<L>): List<String> =
        ls.filter { Txt.has(it.c, "RECURSOSEMDESTAQUE") || Txt.has(it.c, "FEATUREDASSET") }.mapNotNull { h ->
            ls.filter { it !== h && it.cy > h.cy && it.cy - h.cy < 0.025f && abs(it.t.left - h.t.left) < 0.06f && it.t.text.count { c -> c.isLetter() } >= 4 &&
                !Txt.has(it.c, "TAXASDEAPARECIMENTO") }
                .minByOrNull { it.cy }?.t?.text?.trim()
        }.distinct()

    private fun boosters(ls: List<L>): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (n in ls) {
            if (n.t.text.count { it.isLetter() } < 3 || n.t.text.contains("+") || n.cy < 0.2f) continue
            if (listOf("REFORCO", "COLETE", "COMPRAR", "PRECISA", "CORRIDA", "EQUIPE").any { Txt.has(n.c, it) }) continue
            val count = ls.firstOrNull { c -> c !== n && c.cy > n.cy && c.cy - n.cy < 0.04f && abs(c.t.left - n.t.left) < 0.06f && Regex("""^\d{1,3}$""").matches(c.t.text.trim()) }
            if (count != null) out[n.t.text.trim()] = count.t.text.trim().toInt()
        }
        return out
    }

    private val TIER_NAMES = listOf("JUNIOR" to "Júnior", "COMPETIDOR" to "Competidor", "CONTENDOR" to "Contendor", "CAMPEAO" to "Campeão")

    private fun readAsset(ls: List<L>, all: String): AssetDetail? {
        // Nome em duas linhas no topo: "Gabriel" / "BORTOLETO".
        val top = ls.filter { it.t.top < 0.2f && it.t.text.count { c -> c.isLetter() } >= 3 && !Txt.has(it.c, "LIMITACAO") && !Txt.has(it.c, "BONUS") &&
            !Txt.has(it.c, "DISPONIVEL") && !Txt.has(it.c, "PATROCINADOR") && !Txt.has(it.c, "TROFEUS") && !Txt.has(it.c, "MANTENHA") && !Txt.has(it.c, "ESTATISTICAS") }
        val surname = top.firstOrNull { l -> l.t.text.trim().let { t -> t.length >= 3 && t == t.uppercase() && t.any { it.isLetter() } } &&
            listOf("RARO", "COMUM", "EPICO", "LENDARIO", "ESPECIAL", "ESCOLHAS").none { Txt.has(l.c, it) } } ?: return null
        val first = top.lastOrNull { it.cy < surname.cy && surname.cy - it.cy < 0.04f && it.t.text.first().isUpperCase() && it.t.text != it.t.text.uppercase() }
        val name = listOfNotNull(first?.t?.text?.trim(), surname.t.text.trim()).joinToString(" ")
        val rarity = when {
            Txt.has(all, "LENDARIO") && !Txt.has(all, "PILOTOSLENDARIOS") -> com.leandro.f1clashcoach.core.model.Rarity.LEGENDARY
            Txt.has(all, "ESPECIAL") -> com.leandro.f1clashcoach.core.model.Rarity.SPECIAL
            ls.any { it.t.top < 0.2f && Txt.has(it.c, "EPICO") } -> com.leandro.f1clashcoach.core.model.Rarity.EPIC
            ls.any { it.t.top < 0.2f && it.c.startsWith("RAR") } -> com.leandro.f1clashcoach.core.model.Rarity.RARE
            ls.any { it.t.top < 0.2f && Txt.has(it.c, "COMUM") } -> com.leandro.f1clashcoach.core.model.Rarity.COMMON
            else -> com.leandro.f1clashcoach.core.model.Rarity.UNKNOWN
        }
        val availIdx = ls.indexOfFirst { Txt.has(it.c, "DISPONIVELEM") && it.t.top < 0.25f }
        val tierZone = if (availIdx >= 0) ls.drop(availIdx).take(3).joinToString("") { it.c } else ""
        val minTier = TIER_NAMES.firstOrNull { (k, _) -> Txt.has(tierZone, k) }?.second
        val cards = ls.filter { it.t.top < 0.3f }.firstNotNullOfOrNull { CARDS.find(Txt.digitsFix(it.t.text.trim())) }
        val costLine = labelLine(ls, "ATUALIZAR", "UPGRADE") ?: ls.firstOrNull { it.c.startsWith("ATUALI") && it.c.length <= 10 }
        val cost = costLine?.let { l -> ls.filter { sameRow(it, l) && it !== l }.firstNotNullOfOrNull { Txt.bigNumber(it.t.text) }?.takeIf { it >= 1000 } }
        val comparing = Txt.has(all, "ATRIBUIRAOCARRO")
        var stats: DriverStats? = null; var gain: DriverStats? = null
        if (!comparing) {
            val labels = listOf(listOf("ULTRAPASSANDO"), listOf("DEFESA"), listOf("QUALIFICACAO"), listOf("INICIODECORRIDA", "INICIODE"), listOf("GERENCIAMENTODEPNEUS", "GERENCIAMENTO"))
            // Valor e incremento ficam à direita do rótulo (ou na linha logo acima, se o rótulo quebrou).
            fun rowVals(kws: List<String>): Pair<Int?, Int?> {
                val l = labelLine(ls, *kws.toTypedArray()) ?: return null to null
                val near = ls.filter { it !== l && it.t.left > l.t.left + 0.2f && abs(it.cy - l.cy) < 0.025f }
                val v = near.firstNotNullOfOrNull { Regex("""^(\d{1,3})$""").find(Txt.digitsFix(it.t.text.trim()))?.value?.toIntOrNull() }
                val g = near.firstNotNullOfOrNull { Regex("""^\+(\d{1,2})$""").find(it.t.text.trim())?.groupValues?.get(1)?.toIntOrNull() }
                return v to g
            }
            var rows = labels.map { rowVals(it) }
            // Se algum rótulo não foi lido, usa a ordem fixa das 5 linhas (valor à direita + incremento).
            val header = labelLine(ls, "ESTATISTICASDOPILOTOATUAL", "ESTATISTICASDAPECAATUAL")
            if (header != null && rows.any { it.first == null }) {
                val vals = ls.filter { it.cy > header.cy && it.cy - header.cy < 0.25f && it.cx > 0.6f && Regex("""^\d{1,3}$""").matches(Txt.digitsFix(it.t.text.trim())) }
                    .sortedBy { it.cy }
                if (vals.size == 5) rows = vals.map { v ->
                    Txt.digitsFix(v.t.text.trim()).toInt() to ls.firstOrNull { o -> o !== v && sameRow(o, v) && o.t.text.trim().startsWith("+") }
                        ?.let { Regex("""\+(\d{1,2})""").find(it.t.text)?.groupValues?.get(1)?.toIntOrNull() }
                }
            }
            if (rows.count { it.first != null } >= 3) {
                stats = DriverStats(rows[0].first ?: 0, rows[1].first ?: 0, rows[2].first ?: 0, rows[3].first ?: 0, rows[4].first ?: 0)
                if (rows.count { it.second != null } >= 3) gain = DriverStats(rows[0].second ?: 0, rows[1].second ?: 0, rows[2].second ?: 0, rows[3].second ?: 0, rows[4].second ?: 0)
            }
        }
        val isDriver = Txt.has(all, "PILOTO") || stats != null || Txt.has(all, "ULTRAPASSANDO")
        return AssetDetail(name, isDriver, rarity, minTier, cards?.groupValues?.get(2)?.toIntOrNull(), cards?.groupValues?.get(3)?.toIntOrNull(),
            cards?.groupValues?.get(1)?.toIntOrNull(), cost, stats, gain)
    }

    private fun featuredInfo(ls: List<L>): List<String> =
        ls.filter { l -> l.t.text.count { it.isLetter() } >= 6 && l.cx in 0.2f..0.8f &&
            listOf("RECURSOS", "ESCOLHAS", "AUMENTO", "ESTATISTICAS", "EVENTO", "MOMENTOS", "ACESSO", "VOLTAR", "ZUMENTO", "ENQUANTO").none { Txt.has(l.c, it) } &&
            !l.t.text.contains("%") && l.t.top > 0.3f && l.t.top < 0.85f
        }.map { it.t.text.trim() }.distinctBy { Txt.compact(it) }

    private val SERIES_NAMES = listOf(
        "GRANDSLAMDEOURO" to 12, "GRANDSLAMDEPRATA" to 11, "GRANDSLAMDEBRONZE" to 10, "VELOCIDADETERMINAL" to 9,
        "INOVACAO" to 8, "PLATAFORMADELANCAMENTO" to 7, "ACELERACAOMAXIMA" to 6, "MANTERPOSICAO" to 5,
    )

    /** Cartão central da seleção de séries de Duelos. */
    private fun readSeries(ls: List<L>, base: GameRead): GameRead {
        val flags = ls.firstOrNull { it.t.top in 0.04f..0.08f && it.cx in 0.3f..0.5f && Regex("""^\d[.,]?\d{3}$""").matches(it.t.text.trim()) }
            ?.let { Txt.bigNumber(it.t.text)?.toInt() }
        val cards = ls.filter { Txt.has(it.c, "VITORIA") && it.t.left in 0.04f..0.45f }.mapNotNull { vit ->
            val x0 = vit.t.left - 0.04f; val x1 = vit.t.left + 0.8f
            val inCard = ls.filter { it.t.left >= x0 && it.t.left <= x1 }
            fun under(lbl: String) = inCard.firstOrNull { Txt.has(it.c, lbl) }?.let { l ->
                inCard.firstOrNull { it.cy > l.cy && it.cy - l.cy < 0.035f && abs(it.cx - l.cx) < 0.12f }
            }
            val win = under("VITORIA")?.let { Regex("""\+(\d{1,3})""").find(it.t.text)?.groupValues?.get(1)?.toIntOrNull() }
            val loss = under("DERROTA")?.let { Regex("""-\s?(\d{1,3})""").find(it.t.text)?.groupValues?.get(1)?.toIntOrNull() }
            val nameLine = inCard.firstOrNull { l -> SERIES_NAMES.any { (k, _) -> Txt.has(l.c, k.take(min(k.length, 13))) } }
            val number = nameLine?.let { l -> SERIES_NAMES.firstOrNull { (k, _) -> Txt.has(l.c, k.take(min(k.length, 13))) }?.second }
                ?: return@mapNotNull null
            val range = inCard.firstOrNull { sameRow(it, vit).not() && it.cy < vit.cy && vit.cy - it.cy < 0.05f && Regex("""^\d{3,4}\s?-\s?\d{3,4}$""").matches(it.t.text.trim()) }
                ?.t?.text?.split("-")?.mapNotNull { it.trim().toIntOrNull() }
            val track = inCard.firstOrNull { it.t.top in 0.56f..0.61f && it.t.text.count { c -> c.isLetter() } >= 5 && !Txt.has(it.c, "VOLTAS") }?.t?.text?.trim()
            val laps = inCard.firstOrNull { it.t.top in 0.56f..0.61f && Txt.has(it.c, "VOLTAS") }?.let { Regex("""(\d{1,2})""").find(it.t.text)?.value?.toIntOrNull() }
            val premios = inCard.firstOrNull { Txt.has(it.c, "PREMIOS") || it.c.startsWith("PRE") && it.t.top in 0.69f..0.73f }
            val prizeCoins = premios?.let { p -> inCard.filter { sameRow(it, p) && it.cx > p.cx }.firstNotNullOfOrNull { Txt.bigNumber(it.t.text)?.takeIf { v -> v >= 10_000 } } }
            val stars = premios?.let { p -> inCard.filter { sameRow(it, p) && it.cx > p.cx + 0.3f && Regex("""^\D?\d{2,3}$""").matches(it.t.text.trim()) }.firstNotNullOfOrNull { Regex("""\d{2,3}""").find(it.t.text)?.value?.toIntOrNull() } }
            val unlock = inCard.firstOrNull { it.t.top in 0.72f..0.76f && Regex("""^\d{1,3}(?:[.,]\d{3})*$""").matches(it.t.text.trim()) }?.let { Txt.bigNumber(it.t.text)?.toInt() }
            val entry = labelLine(inCard, "ENTRADA")?.let { e -> inCard.filter { sameRow(it, e) && it !== e }.firstNotNullOfOrNull { Txt.bigNumber(it.t.text) } }
            val focus = inCard.filter { it.t.top in 0.63f..0.69f && it.t.text.count { c -> c.isLetter() } >= 5 && !Txt.has(it.c, "ESTATS") }.map { it.t.text.trim() }
            com.leandro.f1clashcoach.core.model.SeriesInfo(
                number, SERIES_NAMES.first { it.second == number }.first.let { com.leandro.f1clashcoach.core.model.SeriesCatalog.defaults.firstOrNull { d -> d.number == number }?.name ?: it },
                trackName = track ?: "", laps = laps, scoreMin = range?.getOrNull(0), scoreMax = range?.getOrNull(1), winFlags = win, lossFlags = loss,
                prizeCoins = prizeCoins, prizeStars = stars, entryFee = entry, flagsToUnlock = unlock ?: (if (entry != null) 0 else null), statFocus = focus,
            )
        }
        return base.copy(seriesCards = cards, flags = flags)
    }

    private val LAPTIME = Regex("""(\d{2})[.:](\d{2})[.:](\d{3})""")

    /** Escolha de pneu antes da largada: tempos de volta por composto e faixa de voltas. */
    private fun readTyres(ls: List<L>, all: String, base: GameRead): GameRead {
        val headers = ls.mapNotNull { l ->
            val t = when {
                Txt.has(l.c, "SOFT") -> com.leandro.f1clashcoach.core.model.Tyre.SOFT
                Txt.has(l.c, "MEDIUM") -> com.leandro.f1clashcoach.core.model.Tyre.MEDIUM
                l.c.endsWith("HARD") && l.c.length <= 6 -> com.leandro.f1clashcoach.core.model.Tyre.HARD
                l.c.endsWith("WET") && l.c.length <= 5 -> com.leandro.f1clashcoach.core.model.Tyre.WET
                Txt.has(l.c, "INTER") -> com.leandro.f1clashcoach.core.model.Tyre.INTER
                else -> null
            }
            t?.let { l to it }
        }
        val names = ls.filter { DRIVER_NAME.matches(it.t.text.trim()) && it.t.top < 0.35f }
        val times = LinkedHashMap<String, MutableMap<com.leandro.f1clashcoach.core.model.Tyre, Double>>()
        val ranges = LinkedHashMap<com.leandro.f1clashcoach.core.model.Tyre, IntRange>()
        for (l in ls) {
            val m = LAPTIME.find(l.t.text) ?: continue
            val h = headers.filter { it.first.cy < l.cy && abs(it.first.cx - l.cx) < 0.2f }.maxByOrNull { it.first.cy } ?: continue
            val driver = names.minByOrNull { abs(it.cx - l.cx) }?.t?.text?.trim() ?: if (l.cx < 0.5f) "Piloto 1" else "Piloto 2"
            val sec = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt() + m.groupValues[3].toInt() / 1000.0
            times.getOrPut(driver) { LinkedHashMap() }[h.second] = sec
        }
        for (l in ls) {
            val m = Regex("""VOLTA\w?\s*(\d)\s*-\s*(\d)""").find(Txt.fold(l.t.text)) ?: continue
            val h = headers.filter { it.first.cy < l.cy && abs(it.first.cx - l.cx) < 0.25f }.maxByOrNull { it.first.cy } ?: continue
            ranges.putIfAbsent(h.second, m.groupValues[1].toInt()..m.groupValues[2].toInt())
        }
        return base.copy(tyreLapTimes = times, tyreLapRanges = ranges)
    }

    private val LAP_FRAC = Regex("""^(\d{1,2})\s*[/1Il|]\s*(\d{1,2})$""")

    private fun readRace(ls: List<L>): RaceHud {
        val lapLine = ls.firstOrNull { it.t.top < 0.06f && it.cx in 0.38f..0.62f && LAP_FRAC.matches(it.t.text.trim()) }
        var lap: Int? = null; var total: Int? = null
        lapLine?.let { l ->
            val t = l.t.text.trim()
            val m = Regex("""^(\d{1,2})\s*/\s*(\d{1,2})$""").find(t) ?: LAP_FRAC.find(t)
            if (m != null) { lap = m.groupValues[1].toInt(); total = m.groupValues[2].toInt() }
            if (lap != null && total != null && lap!! > total!!) { lap = null; total = null }
        }
        val topBand = ls.filter { it.t.top < 0.09f }
        val rain = topBand.firstOrNull { it.cx in 0.25f..0.48f && Regex("""^\d{1,3}\s?%$""").matches(it.t.text.trim()) }
            ?.let { Regex("""\d{1,3}""").find(it.t.text)?.value?.toIntOrNull() }
        val temp = topBand.firstOrNull { it.t.text.contains("°") || it.t.text.contains("℃") }?.let { Regex("""(\d{2})\s?°?""").findAll(it.t.text).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() }
        val word = topBand.firstOrNull { it.cx in 0.1f..0.3f && it.t.text.count { c -> c.isLetter() } >= 3 && !it.t.text.contains("F1") && it.t.top > 0.04f }?.t?.text?.trim()
        val wc = word?.let { Txt.compact(it) } ?: ""
        val weather = when {
            Txt.has(wc, "TEMPESTADE") || Txt.has(wc, "CHUVAFORTE") || Txt.has(wc, "HEAVY") -> Weather.HEAVY_RAIN
            wc.contains("CHUV") || wc.contains("GAROA") || wc.contains("RAIN") -> Weather.LIGHT_RAIN
            else -> Weather.DRY
        }
        val timeLeft = topBand.firstOrNull { it.cx in 0.48f..0.65f && Regex("""^\d{1,2}:\d{2}$""").matches(it.t.text.trim()) }?.t?.text?.trim()
        val messages = ls.filter { it.t.top in 0.08f..0.13f && it.t.text.count { c -> c.isLetter() } >= 5 && !Txt.has(it.c, "TOQUEPARAMUDAR") && !Txt.has(it.c, "PADRAO") }.map { it.t.text.trim() }
        val msgAll = Txt.compact(messages.joinToString(" "))
        val rivalTyre = if (Txt.has(msgAll, "RIVAL") && Txt.has(msgAll, "TROCANDO")) when {
            msgAll.contains("SOFT") -> com.leandro.f1clashcoach.core.model.Tyre.SOFT
            msgAll.contains("MEDIUM") -> com.leandro.f1clashcoach.core.model.Tyre.MEDIUM
            msgAll.contains("HARD") -> com.leandro.f1clashcoach.core.model.Tyre.HARD
            msgAll.contains("WET") -> com.leandro.f1clashcoach.core.model.Tyre.WET
            msgAll.contains("INTER") -> com.leandro.f1clashcoach.core.model.Tyre.INTER
            else -> null
        } else null
        val allC = ls.joinToString(" ") { it.c }

        fun side(left: Boolean): DriverHud {
            val hs = ls.filter { (it.cx < 0.5f) == left && it.t.top > 0.7f }
            val ers = hs.firstOrNull { it.t.top in 0.72f..0.79f && Regex("""^\d{1,3}\s?%$""").matches(it.t.text.trim()) }
                ?.let { Regex("""\d{1,3}""").find(it.t.text)?.value?.toIntOrNull() }
            val tyreLife = hs.filter { it.t.top in 0.86f..0.95f && Regex("""^\d{1,3}\s?%$""").matches(it.t.text.trim()) }.maxByOrNull { it.t.top }
                ?.let { Regex("""\d{1,3}""").find(it.t.text)?.value?.toIntOrNull()?.takeIf { v -> v <= 100 } }
            val ovt = when {
                hs.any { Txt.has(it.c, "ATIVADO") || it.c.endsWith("IVADO") } -> OvertakeState.ACTIVE
                hs.any { Txt.has(it.c, "PRONTO") } -> OvertakeState.READY
                else -> null
            }
            val codeLine = hs.firstOrNull { Regex("""^[A-Z][A-Za-z]{2}$""").matches(it.t.text.trim()) && hs.any { v -> sameRow(v, it) && Txt.has(v.c, "VOLTA") } }
            val pos = codeLine?.let { c -> hs.firstOrNull { o -> o !== c && sameRow(o, c) && o.t.right <= c.t.left + 0.01f && Regex("""^\d{1,2}$""").matches(o.t.text.trim()) }?.t?.text?.trim()?.toIntOrNull() }
            val speed = hs.firstOrNull { Regex("""^\d{2,3}$""").matches(it.t.text.trim()) && hs.any { k -> Txt.has(k.c, "KMH") && abs(k.cy - it.cy) < 0.03f && abs(k.cx - it.cx) < 0.2f } }?.t?.text?.trim()?.toIntOrNull()
            val stops = hs.firstNotNullOfOrNull { Regex("""(\d)\D{0,2}PITSTOP""").find(it.c)?.groupValues?.get(1)?.toIntOrNull() }
            val pts = hs.firstNotNullOfOrNull { Regex("""\+(\d{1,2})\s?PTS""").find(it.t.text.replace(" ", ""))?.groupValues?.get(1)?.toIntOrNull() }
            return DriverHud(
                left = left, code = codeLine?.t?.text?.trim()?.uppercase(), position = pos, ers = ers, tyreLife = tyreLife, overtake = ovt, speedKmh = speed,
                pitRequested = hs.any { Txt.has(it.c, "CANCELARPITSTOP") }, inPit = hs.any { Txt.has(it.c, "TROCANDOPNEUS") }, stops = stops, finishPoints = pts,
            )
        }
        return RaceHud(
            lap = lap, totalLaps = total, weatherWord = word, weather = weather, rainChance = rain, tempC = temp, timeLeft = timeLeft,
            messages = messages, drivers = listOf(side(true), side(false)), rivalPitTyre = rivalTyre,
            tyreWarning = Txt.has(msgAll + allC, "TOTALMENTEDESGASTADOS") || Txt.has(allC, "PNEUSESTAOQUASE"),
            lastLap = Txt.has(msgAll, "ULTIMAVOLTA"), finished = ls.count { it.c == "FIM" } >= 1,
        )
    }

    private fun readDuel(ls: List<L>, all: String): DuelResult {
        val winner = ls.firstOrNull { Txt.has(it.c, "VENCEDOR") }
        val ptsLines = ls.mapNotNull { l -> Regex("""^(\d{1,3})\s?PTS$""", RegexOption.IGNORE_CASE).find(l.t.text.trim())?.let { l to it.groupValues[1].toInt() } }
        // Tela VENCEDOR: placar grande dos dois lados; a sua equipe fica à esquerda.
        val mine = ptsLines.filter { it.first.cx < 0.5f }.maxByOrNull { it.first.t.bottom - it.first.t.top }?.second
        val rival = ptsLines.filter { it.first.cx >= 0.5f && it.first.t.left < 0.8f }.firstOrNull()?.second
        val won = winner?.let { it.cx < 0.5f }
        val flags = ls.firstOrNull { winner != null && Regex("""^\d{4,5}$""").matches(it.t.text.trim()) && it.t.top in 0.45f..0.6f }?.t?.text?.trim()?.toIntOrNull()
        return DuelResult(won, if (winner != null) mine else null, if (winner != null) rival else null, flags, Txt.has(all, "MELHORTEMPO") || Txt.has(all, "FASTESTLAP"))
    }

    private fun readClub(ls: List<L>, all: String, base: GameRead): GameRead {
        val name = ls.firstOrNull { it.t.top < 0.07f && it.t.bottom > 0.03f && it.t.text.any { c -> c.isLetter() } }?.t?.text?.trim()
        val repLabel = labelLine(ls, "REPUTACAO", "REPUTATION") ?: ls.firstOrNull { it.c.startsWith("REPUT") }
        val rep = repLabel?.let { l -> ls.filter { it.cy > l.cy && it.cy - l.cy < 0.04f && abs(it.cx - l.cx) < 0.1f }.firstNotNullOfOrNull { Txt.bigNumber(it.t.text) } }
        val league = ls.firstOrNull { Regex("""EXIBICAO\d{1,3}""").containsMatchIn(it.c) }
        val leagueLeft = league?.let { lg -> ls.filter { it.cy >= lg.cy && it.cy - lg.cy < 0.04f }.firstNotNullOfOrNull { Txt.duration(it.t.text) } }
        val count = ls.firstNotNullOfOrNull { Regex("""(\d{1,2})\s*/\s*50$""").find(it.t.text.trim())?.groupValues?.get(1)?.toIntOrNull() }
            ?.let { if (it > 50) it % 100 else it }
        return base.copy(clubName = name, clubRep = rep, clubLeagueName = league?.t?.text?.trim(), clubLeagueLeftMs = leagueLeft,
            clubMembers = if (league == null) readClubMembers(ls) else emptyList(), clubMemberCount = count?.takeIf { league == null }, clubRules = readClubRules(ls))
    }

    private val ROLES = linkedMapOf("VICECAPITAO" to "Vice-Capitão", "PRESIDENTE" to "Presidente", "CAPITAO" to "Capitão", "MEMBRO" to "Membro")

    private fun roleOf(l: L): String? {
        val c = l.c.replace('Z', 'A')
        if (c.length > 14) return null
        return ROLES.entries.firstOrNull { (k, _) -> c == k || (c.length >= k.length - 1 && Txt.lev(c, k, 2) <= (if (k.length >= 7) 2 else 1)) }?.value
    }

    /** Aba Membros: nome com o cargo logo abaixo e a reputação à direita, na mesma linha do nome. */
    private fun readClubMembers(ls: List<L>): List<ClubMemberRead> {
        val header = ls.firstOrNull { Txt.has(it.c, "RANKING") } ?: return emptyList()
        val out = ArrayList<ClubMemberRead>()
        for (role in ls.filter { it.cy > header.cy && it.cx in 0.2f..0.6f && it.t.left in 0.2f..0.35f }) {
            val r = roleOf(role) ?: continue
            val name = ls.filter { it !== role && it.t.left in 0.2f..0.35f && it.cy < role.cy && role.cy - it.cy < 0.035f && roleOf(it) == null }
                .maxByOrNull { it.cy } ?: continue
            val rep = ls.filter { it.cx > 0.7f && abs(it.cy - name.cy) < 0.02f }
                .firstNotNullOfOrNull { l -> Regex("""^[\d.,]+$""").find(l.t.text.trim())?.let { Txt.bigNumber(it.value) } } ?: continue
            out += ClubMemberRead(name.t.text.trim(), r, rep)
        }
        return out
    }

    /** Regras do clube: frases da descrição que falam de mínimo, reputação, inatividade ou expulsão. */
    private fun readClubRules(ls: List<L>): String? {
        val kws = listOf("REGRA", "MINIMO", "OBRIGATORIO", "INATIV", "EXPULS", "REMOVID", "BANID", "SEMANA", "DIAS")
        val hits = ls.filter { l -> l.t.text.length >= 25 && l.t.text.any { it.isLowerCase() } && kws.any { Txt.has(l.c.replace('Z', 'A'), it) || l.c.contains(it) } }
        if (hits.isEmpty()) return null
        val first = hits.minOf { it.cy }
        val last = hits.maxOf { it.cy }
        return ls.filter { it.cy in (first - 0.005f)..(last + 0.005f) && it.t.text.length >= 3 }.joinToString(" ") { it.t.text.trim() }.take(600)
    }
}
