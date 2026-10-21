package com.leandro.f1clashcoach.capture

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import com.leandro.f1clashcoach.CoachApp
import com.leandro.f1clashcoach.core.ai.AiImage
import com.leandro.f1clashcoach.core.ai.AiRequest
import com.leandro.f1clashcoach.core.ai.CoachPrompts
import com.leandro.f1clashcoach.core.engine.DailyBriefing
import com.leandro.f1clashcoach.core.engine.GameScreen
import com.leandro.f1clashcoach.core.engine.LiveCoach
import com.leandro.f1clashcoach.core.engine.ParsedScreen
import com.leandro.f1clashcoach.core.engine.RaceContext
import com.leandro.f1clashcoach.core.engine.RaceStrategist
import com.leandro.f1clashcoach.core.engine.SetupOptimizer
import com.leandro.f1clashcoach.core.engine.WearModel
import com.leandro.f1clashcoach.core.model.DrivingPace
import com.leandro.f1clashcoach.core.model.GameMode
import com.leandro.f1clashcoach.core.model.RaceRecord
import com.leandro.f1clashcoach.core.model.Track
import com.leandro.f1clashcoach.core.model.Tyre
import com.leandro.f1clashcoach.core.model.TyreObservation
import com.leandro.f1clashcoach.core.model.Weather
import com.leandro.f1clashcoach.data.ChatMessage
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.graphics.Bitmap
import com.leandro.f1clashcoach.ai.Diag

/**
 * Cérebro da leitura: recebe cada foto da tela (de qualquer forma de captura), roda o OCR local,
 * reconhece a tela do F1 Clash, atualiza a conta e mostra no painel o que fazer.
 */
class FrameProcessor(
    private val app: CoachApp,
    private val overlay: OverlayController,
    private val scope: CoroutineScope,
) {
    private val ocr = OcrEngine()
    // Estado da corrida atual
    private var currentTrack: Track? = null
    private var lastTotalLaps: Int? = null
    private var lastWeather = Weather.DRY
    private var lastResultAt = 0L
    private var lastScanName: String? = null
    private var lastGrid: List<Int> = emptyList()
    private var lastRaceSeenAt = 0L
    private val stints = HashMap<Int, StintTrack>()
    private val usedTyres = LinkedHashSet<Tyre>()

    private data class StintTrack(val tyre: Tyre, val startLap: Int, val startLife: Int, var lastLap: Int, var lastLife: Int)

    /** Processa uma foto da tela. Devolve a tela reconhecida. */
    suspend fun onFrame(bitmap: Bitmap): GameScreen {
        LiveState.lastFrame = bitmap
        Diag.framesRead++
        val t0 = System.currentTimeMillis()
        val lines = try { ocr.read(bitmap) } catch (e: Exception) {
            Diag.error("OCR: " + (e.message ?: e.javaClass.simpleName)); return GameScreen.UNKNOWN
        }
        Diag.lastOcrMs = System.currentTimeMillis() - t0
        Diag.lastOcrLines = lines.size
        Diag.lastOcr = lines.joinToString(" | ") { it.text }.take(1500)
        val parsed = try { com.leandro.f1clashcoach.core.engine.ScreenReader.parse(lines, lastSlot) } catch (e: Exception) {
            Diag.error("Leitura: " + (e.message ?: e.javaClass.simpleName)); return GameScreen.UNKNOWN
        }
        if (parsed.screen != GameScreen.UNKNOWN) {
            Diag.recognized++
            if (Diag.lastScreen != parsed.screen.label) Diag.log("Tela: ${parsed.screen.label}")
            Diag.lastScreen = parsed.screen.label
            Diag.lastScreenAt = System.currentTimeMillis()
        }
        try {
            val rich = withRarity(parsed, bitmap)
            // fotos das cartas para a garagem (recortadas da sua própria tela)
            rich.game?.tiles?.takeIf { it.isNotEmpty() && (rich.screen == GameScreen.TEAM_DRIVERS || rich.screen == GameScreen.TEAM_CARS) }?.let { tl ->
                runCatching { com.leandro.f1clashcoach.art.ArtStore.captureTiles(app, bitmap, tl, rich.screen == GameScreen.TEAM_CARS) }
            }
            handle(rich)
        } catch (e: Exception) {
            Diag.error("Tela ${parsed.screen.label}: " + (e.message ?: e.javaClass.simpleName))
        }
        LiveState.update { it.copy(framesRead = it.framesRead + 1, lastParsed = parsed, screen = parsed.screen) }
        return parsed.screen
    }

    fun close() { runCatching { ocr.close() } }

    /** Cartas de piloto: a cor do fundo diz a raridade (azul comum, laranja rara, roxo épica). */
    private fun withRarity(p: ParsedScreen, bmp: Bitmap): ParsedScreen {
        val g = p.game ?: return p
        if (p.screen != GameScreen.TEAM_DRIVERS || g.tiles.isEmpty()) return p
        val w = bmp.width; val h = bmp.height
        val tiles = g.tiles.map { t ->
            val px = com.leandro.f1clashcoach.core.model.RarityColors.offsets.mapNotNull { (dx, dy) ->
                val x = ((t.nameLeft + dx) * w).toInt(); val y = ((t.nameTop + dy) * h).toInt()
                if (x in 0 until w && y in 0 until h) bmp.getPixel(x, y).let { c -> Triple((c shr 16) and 0xff, (c shr 8) and 0xff, c and 0xff) } else null
            }
            if (t.rarity != com.leandro.f1clashcoach.core.model.Rarity.UNKNOWN) t else t.copy(rarity = com.leandro.f1clashcoach.core.model.RarityColors.classify(px))
        }
        return p.copy(game = g.copy(tiles = tiles))
    }

    private fun handle(p: ParsedScreen) {
        val data = app.store.data.value
        val settings = app.settings.state.value
        p.track?.let { if (it != currentTrack) onTrackDetected(it) }
        // voltas só da corrida (os cartões de série no menu também mostram "N voltas" e confundiam)
        if (p.screen == GameScreen.RACE) p.totalLaps?.let { lastTotalLaps = it }
        p.weather?.let { lastWeather = it }

        when (p.screen) {
            GameScreen.RACE -> {
                lastRaceSeenAt = System.currentTimeMillis()
                val tm = data.garage.drivers.filter { it.equipped }.map { it.stats.tyreManagement }.ifEmpty { listOf(50) }.average().toInt()
                val hud0 = p.game?.race
                if (hud0 != null) {
                    // O OCR não lê a letra do composto: identifica pela cor do ícone do pneu.
                    val frame = LiveState.lastFrame
                    val hud = if (frame != null) hud0.copy(drivers = hud0.drivers.mapIndexed { i, d ->
                        val tyre = smoothTyre(i, sampleTyre(frame, if (i == 0) 0.025f else 0.525f))
                        d.copy(tyre = tyre ?: d.tyre)
                    }) else hud0
                    hud.totalLaps?.let { lastTotalLaps = it }
                    lastWeather = hud.weather
                    lastRaceHud = hud
                    val names = data.garage.drivers.filter { it.equipped }.map { it.name }
                    // Pit: desgaste medido nesta corrida (não modelo). As dicas antigas de desgaste estimado saem.
                    val total = hud.totalLaps ?: lastTotalLaps
                    hud.drivers.forEachIndexed { i, d -> liveWear.observe(i, hud.lap, d.tyre, d.tyreLife) }
                    val pitTips = hud.drivers.indices.mapNotNull { i ->
                        val d = hud.drivers[i]
                        liveWear.call(i, hud.lap, total, d.tyre, d.tyreLife, lastRanges, currentLapTimes, hud.weather != Weather.DRY)
                    }.map { c -> com.leandro.f1clashcoach.core.engine.Tip(if (c.urgent) com.leandro.f1clashcoach.core.engine.TipLevel.URGENT else if (c.pitAtEndOfLap != null) com.leandro.f1clashcoach.core.engine.TipLevel.IMPORTANT else com.leandro.f1clashcoach.core.engine.TipLevel.INFO, c.text, "") }
                    LiveState.update { it.copy(race = it.race.copy(lap = hud.lap, laps = total, pitLines = pitTips.map { t -> t.title }, updatedAt = System.currentTimeMillis())) }
                    val modeled = setOf("CONSERVAR", "pare nesta volta", "pode ATACAR")
                    val tips = (pitTips + LiveCoach(WearModel(data.tyreObservations)).adviseHud(hud, currentTrack, tm, names)
                        .filter { t -> modeled.none { t.title.contains(it) } }).sortedBy { it.level.ordinal }
                    LiveState.update { it.copy(tips = tips, track = currentTrack) }
                    val rain = hud.rainChance?.let { " • ☔$it%" } ?: ""
                    overlay.setHeader("🏁 V${hud.lap ?: "?"}/${hud.totalLaps ?: lastTotalLaps ?: "?"} • ${currentTrack?.name ?: "corrida"}$rain")
                    overlay.showTips(tips, footer = hud.drivers.joinToString("  ") { d ->
                        "${d.code ?: "?"}: ${d.tyre?.short ?: "?"} ${d.tyreLife ?: "?"}% ⚡${d.ers ?: "?"}%"
                    })
                    if (settings.learnTyreWear) trackTyres(hud.toSnapshot().drivers.mapIndexed { i, d -> i to d }, hud.lap)
                    hud.drivers.mapNotNull { it.tyre }.forEach { usedTyres += it }
                } else {
                    if (p.lap == 1 && p.positions.isNotEmpty()) lastGrid = p.positions
                    val snapshot = p.toSnapshot()
                    val tips = LiveCoach(WearModel(data.tyreObservations)).advise(snapshot, currentTrack, tm)
                    LiveState.update { it.copy(tips = tips, track = currentTrack) }
                    overlay.setHeader("🏁 V${p.lap ?: "?"}/${p.totalLaps ?: lastTotalLaps ?: "?"} • ${currentTrack?.name ?: "pista ?"}")
                    overlay.showTips(tips)
                    if (settings.learnTyreWear) trackTyres(snapshot.drivers.mapIndexed { i, d -> i to d }, p.lap)
                }
            }
            GameScreen.RACE_RESULT -> onResult(p, settings.autoRecordResults)
            GameScreen.PARTS, GameScreen.DRIVERS, GameScreen.UPGRADE -> if (settings.autoScanGarage) scanGarage(p)
            GameScreen.HOME, GameScreen.RACE_MENU, GameScreen.GP, GameScreen.OBJECTIVES, GameScreen.TEAM_DRIVERS,
            GameScreen.TEAM_CARS, GameScreen.BOOSTERS, GameScreen.CLUB, GameScreen.PIT_PASS, GameScreen.STORE,
            GameScreen.ASSET_DETAIL, GameScreen.FEATURED, GameScreen.DUEL_SERIES, GameScreen.PRE_RACE_TYRES, GameScreen.PRE_RACE,
            GameScreen.QUALIFYING -> onGameScreen(p)
            else -> {
                val mode = p.mode
                if (mode != null && mode != GameMode.CLUB && mode != GameMode.SEASON && p.title != null &&
                    (p.screen == GameScreen.EVENT || p.timeLeftMs != null)
                ) onEventScreen(mode, p.title!!, p.timeLeftMs)
                else overlay.setHeader("🏁 F1 Coach • ${mode?.label ?: p.screen.label}")
            }
        }
        if (p.screen != GameScreen.TEAM_CARS) lastSlot = null
    }

    /** Último slot de peça visto (a grade rola e o cabeçalho some da tela). */
    @Volatile private var lastSlot: com.leandro.f1clashcoach.core.model.PartSlot? = null

    /** Telas de menu do jogo: sincroniza a conta e mostra no painel o que importa naquela tela. */
    private fun onGameScreen(p: ParsedScreen) {
        val g = p.game ?: return
        g.tiles.lastOrNull { it.slot != null }?.slot?.let { lastSlot = it }
        val changes = if (app.settings.state.value.autoScanGarage) app.store.applyGameRead(g) else emptyList()
        changes.take(3).forEach { LiveState.event(it) }
        val d = app.store.data.value
        val now = System.currentTimeMillis()
        overlay.setHeader("🏁 F1 Coach • ${p.screen.label}")
        when (p.screen) {
            GameScreen.GP, GameScreen.RACE_MENU -> g.gp?.let { gp ->
                val items = DailyBriefing.build(d.garage, d.races, d.events, d.tyreObservations, d.lastMetaTime, now, d.objectives, d.gp)
                    .filter { it.icon in setOf("🏆", "⛔", "⭐") }
                val advice = (com.leandro.f1clashcoach.core.engine.GpPlanner.plan(d.garage, d.gp).lines.joinToString("\n") + "\n\n" +
                    items.joinToString("\n\n") { "${it.icon} ${it.title}\n${it.detail}" }).trim()
                onEventScreen(GameMode.GRAND_PRIX, listOfNotNull(gp.name, gp.round).joinToString(" — "), gp.timeLeftMs, advice)
            }
            GameScreen.PRE_RACE_TYRES -> if (g.tyreLapTimes.isNotEmpty() || g.tyreLapRanges.isNotEmpty()) {
                // Só dados do próprio jogo: voltas totais, faixa de voltas de cada pneu e tempo por volta.
                val avg = com.leandro.f1clashcoach.core.model.Tyre.entries.mapNotNull { t ->
                    g.tyreLapTimes.values.mapNotNull { it[t] }.takeIf { it.isNotEmpty() }?.average()?.let { t to it }
                }.toMap()
                if (avg.isNotEmpty()) currentLapTimes = avg
                if (g.tyreLapRanges.isNotEmpty()) lastRanges = g.tyreLapRanges
                g.totalLaps?.let { lastTotalLaps = it }
                val laps = g.totalLaps ?: lastTotalLaps
                val track = currentTrack ?: com.leandro.f1clashcoach.core.model.TrackCatalog.byId(d.gp?.trackId)
                val pitLoss = (d.garage.carTotals?.pitStop?.takeIf { it > 0 } ?: 4.0) + 4.0
                val plans = if (laps != null) com.leandro.f1clashcoach.core.engine.RangePlanner.plans(laps, lastRanges, currentLapTimes, pitLoss) else emptyList()
                val stops = plans.firstOrNull()?.stops ?: 1
                val boost = com.leandro.f1clashcoach.core.engine.Boosters.plan(d.garage.boosters, track, stops, lastWinPct, important = d.gp != null && System.currentTimeMillis() - (d.gp?.seenAt ?: 0) < 600_000)
                LiveState.update { it.copy(race = it.race.copy(trackName = track?.name, laps = laps, ranges = lastRanges, lapTimes = currentLapTimes,
                    plans = plans.map { pl -> pl.summary() }, boosterLines = boost.lines, updatedAt = System.currentTimeMillis())) }
                overlay.showMessage(buildString {
                    append("🛞 ${track?.name ?: "Corrida"}")
                    append(if (laps != null) " • $laps voltas\n" else " • lendo voltas…\n")
                    if (lastRanges.isNotEmpty()) append("Faixas do jogo: " + lastRanges.entries.joinToString("  ") { "${it.key.short} ${it.value.first}-${it.value.last}v" } + "\n")
                    plans.take(2).forEachIndexed { i, pl -> append(if (i == 0) "✅ " else "• ").append(pl.summary()).append('\n') }
                    if (boost.lines.isNotEmpty()) { append("\n🧪 Reforços (dos que você tem):\n"); boost.lines.forEach { append(it).append('\n') } }
                    else if (d.garage.boosters.isEmpty()) append("\n🧪 Abra Equipe › Reforços uma vez para eu saber quantos você tem.")
                }.trim(), pin = false)
            }
            GameScreen.PRE_RACE -> g.rivalTeamScore?.let { rival ->
                val mine = d.garage.teamScore
                lastRivalScore = rival
                newRace()
                if (mine > 0) {
                    val s = com.leandro.f1clashcoach.core.model.SeriesCatalog.merged(d.series).firstOrNull { it.number == d.garage.series }
                    val odds = com.leandro.f1clashcoach.core.engine.DuelOddsCalc.estimate(mine, rival, d.races, s)
                    lastWinPct = if (odds.confident) odds.winPct else when (odds.verdict) { "Favorito" -> 75; "Equilibrado" -> 55; else -> 35 }
                    LiveState.update { it.copy(race = RaceInfo(odds = odds.headline + " • você $mine x rival $rival", updatedAt = System.currentTimeMillis())) }
                    overlay.showMessage(
                        "🆚 ${odds.headline}\nVocê $mine x rival $rival" +
                            (odds.expectedFlags?.let { " • ${if (it >= 0) "+" else ""}${"%.1f".format(it)} 🏳 esperadas" } ?: "") +
                            "\n" + odds.tips.take(2).joinToString("\n") { "• $it" },
                        pin = false,
                    )
                } else overlay.showMessage("🆚 Rival: pontuação $rival. Abra Equipe › Carros no jogo para eu saber a sua e calcular a chance.", pin = false)
            }
            GameScreen.DUEL_SERIES -> {
                val prog = com.leandro.f1clashcoach.core.engine.Progression.plan(d.garage, com.leandro.f1clashcoach.core.model.SeriesCatalog.merged(d.series), d.races, d.flags)
                overlay.showMessage("🏁 Rumo à Série 12 • bandeiras ${d.flags ?: "?"}\n" + prog.steps.joinToString("\n") { st ->
                    "S${st.series.number}: faltam ${st.flagsMissing ?: "?"} 🏳" + (st.scoreGap?.takeIf { it > 0 }?.let { " • +$it pontuação" } ?: "")
                } + (prog.advice.firstOrNull()?.let { "\n💡 $it" } ?: ""), pin = false)
            }
            GameScreen.ASSET_DETAIL -> g.asset?.let { a ->
                val tier = a.minTier?.let { " • GP: $it+" } ?: ""
                overlay.showMessage("🔎 ${a.name} (${a.rarity.label})$tier" + (a.upgradeCost?.let { "\nUpgrade: ${it / 1000}k moedas" } ?: "") +
                    (a.driverGain?.let { gn -> "\nGanho: ULT +${gn.overtaking} DEF +${gn.defending} QUA +${gn.qualifying} LAR +${gn.raceStart} PNEU +${gn.tyreManagement}" } ?: ""), pin = false)
            }
            GameScreen.PIT_PASS, GameScreen.HOME -> {
                if (g.seasonName != null && g.seasonLeftMs != null) app.store.upsertEvent(GameMode.SEASON, "Pit Pass ${g.seasonName}", g.seasonLeftMs)
                if (g.freeCrateReady) overlay.showMessage("🎁 Caixote grátis disponível — abra antes de correr.", pin = false)
            }
            GameScreen.CLUB -> {
                if (g.clubLeagueName != null) app.store.upsertEvent(GameMode.CLUB, "Liga de clubes ${g.clubLeagueName}", g.clubLeagueLeftMs)
                if (g.clubMembers.isNotEmpty()) onClubMembers()
            }
            GameScreen.OBJECTIVES -> {
                val pend = d.objectives.filter { !it.done }.sortedBy { it.weekly }
                overlay.showMessage(
                    if (pend.isEmpty()) "✅ Todos os objetivos lidos estão completos."
                    else "📋 Objetivos pendentes:\n" + pend.take(5).joinToString("\n") { "• ${it.text.take(60)} (${it.progress}/${it.target})\n   ${DailyBriefing.objectiveHint(it.text)}" },
                    pin = false,
                )
            }
            GameScreen.TEAM_CARS, GameScreen.TEAM_DRIVERS -> {
                val ready = com.leandro.f1clashcoach.core.engine.UpgradePlanner.plan(d.garage).ranked.filter { it.canUpgradeNow }
                overlay.showMessage(
                    "🔧 Garagem: ${d.garage.parts.size} peças • ${d.garage.drivers.size} pilotos • pontuação ${d.garage.teamScore}\n" +
                        (if (ready.isEmpty()) "Nenhum item com cartas suficientes." else "⬆️ Prontos para evoluir: ${ready.take(4).joinToString { it.name }}" + if (ready.size > 4) " (+${ready.size - 4})" else "") +
                        (d.garage.drivers.filter { it.equipped && it.legendary }.takeIf { it.isNotEmpty() }?.let { "\n👑 Lendário escalado: ${it.joinToString { x -> x.name }} (alguns GPs proíbem)" } ?: ""),
                    pin = false,
                )
            }
            GameScreen.STORE -> if (g.featured.isNotEmpty() && com.leandro.f1clashcoach.core.engine.StoreWatch.matches(g.featured, com.leandro.f1clashcoach.core.engine.StoreWatch.wishlist(d.garage)).isNotEmpty()) {
                val hit = com.leandro.f1clashcoach.core.engine.StoreWatch.matches(g.featured, com.leandro.f1clashcoach.core.engine.StoreWatch.wishlist(d.garage))
                overlay.showMessage("🛒 Na loja tem carta que você precisa: ${hit.take(4).joinToString()}\nVale gastar moedas/notas aqui antes de outras ofertas.", pin = false)
                val day = java.time.LocalDate.now().toString()
                if (storeNotified != day + hit.joinToString()) {
                    storeNotified = day + hit.joinToString()
                    com.leandro.f1clashcoach.notify.Notifier.show(app, 4100, "🛒 Carta que você precisa na loja", hit.take(4).joinToString())
                }
            } else if (d.garage.featuredAssets.isNotEmpty()) {
                val mine = (d.garage.drivers.filter { it.featured }.map { it.name } + d.garage.parts.filter { it.featured }.map { it.name })
                overlay.showMessage(
                    "⭐ Destaques da semana: ${d.garage.featuredAssets.take(6).joinToString()}" +
                        (if (mine.isNotEmpty()) "\nVocê já tem: ${mine.take(4).joinToString()} — priorize evoluir/usar esses." else "\nVocê não tem nenhum destaque: só compre se for peça/piloto que você vai usar."),
                    pin = false,
                )
            }
            else -> Unit
        }
    }

    private var lastAutoAiAt = 0L
    @Volatile private var lastRaceHud: com.leandro.f1clashcoach.core.engine.RaceHud? = null
    @Volatile private var currentLapTimes: Map<Tyre, Double> = emptyMap()
    private val tyreHistory = HashMap<Int, ArrayDeque<Tyre?>>()

    /** Amostra os pixels do ícone do pneu (canto inferior de cada painel de piloto). */
    private fun sampleTyre(frame: android.graphics.Bitmap, x0: Float): Tyre? = runCatching {
        val w = frame.width; val h = frame.height
        val px = ArrayList<Triple<Int, Int, Int>>()
        var y = (h * 0.925f).toInt()
        while (y < (h * 0.947f).toInt()) {
            var x = (w * x0).toInt()
            while (x < (w * (x0 + 0.05f)).toInt()) {
                val c = frame.getPixel(x, y)
                px += Triple((c shr 16) and 0xff, (c shr 8) and 0xff, c and 0xff)
                x += 2
            }
            y += 2
        }
        com.leandro.f1clashcoach.core.engine.TyreColors.vote(px)
    }.getOrNull()

    /** Só aceita a troca de composto se aparecer em 2 leituras seguidas (evita ruído). */
    private fun smoothTyre(i: Int, t: Tyre?): Tyre? {
        val q = tyreHistory.getOrPut(i) { ArrayDeque() }
        q.addLast(t); while (q.size > 3) q.removeFirst()
        val last2 = q.toList().takeLast(2)
        return if (last2.size == 2 && last2[0] == last2[1]) last2[0] else q.firstOrNull { it != null }
    }

    /** Tela de evento/GP/exibição/momentos: registra, mostra a dica e pede análise automática à IA uma vez. */
    private fun onEventScreen(mode: GameMode, title: String, timeLeftMs: Long?, localAdvice: String? = null) {
        val (ev, isNew) = app.store.upsertEvent(mode, title, timeLeftMs)
        val left = ev.remainingMs(System.currentTimeMillis())?.let { " • faltam ${DailyBriefing.formatLeft(it)}" } ?: ""
        overlay.setHeader("🎯 ${mode.label}$left")
        if (isNew) {
            LiveState.event("${mode.label} detectado: $title")
            overlay.showMessage("${mode.label}: $title$left\n\n${localAdvice?.takeIf { it.isNotBlank() } ?: mode.tip}", pin = false)
        } else if (ev.aiAdvice.isBlank() && !localAdvice.isNullOrBlank()) {
            overlay.showMessage("${mode.label}: $title$left\n\n$localAdvice", pin = false)
        } else if (ev.aiAdvice.isNotBlank()) {
            overlay.showMessage("${mode.label}: $title$left\n\n${ev.aiAdvice}", pin = false)
        }
        val s = app.settings.state.value
        val now = System.currentTimeMillis()
        if (ev.aiAdvice.isBlank() && com.leandro.f1clashcoach.ai.AiClient.hasAnyKey(app) && now - lastAutoAiAt > 90_000) {
            lastAutoAiAt = now
            val frame = LiveState.lastFrame ?: return
            scope.launch {
                runCatching {
                    val d = app.store.data.value
                    val advisor = com.leandro.f1clashcoach.ai.AiHub(app)
                    val ctx = CoachPrompts.accountContext(d.garage, d.races, currentTrack, d.events, System.currentTimeMillis(), d.objectives, d.gp, com.leandro.f1clashcoach.core.model.SeriesCatalog.merged(d.series), d.flags)
                    advisor.ask(AiRequest(CoachPrompts.system(), CoachPrompts.withContext(CoachPrompts.eventAnalysis(mode.label, title), ctx),
                        image = AiImage(ImageUtils.toJpeg(frame), "image/jpeg"), webSearch = true))
                }.onSuccess { a ->
                    app.store.setEventAdvice(ev.id, a.text)
                    overlay.showMessage("🤖 ${mode.label}: $title\n\n${a.text}")
                    LiveState.event("IA analisou: $title")
                }.onFailure { LiveState.event("IA não analisou $title: ${it.message}") }
            }
        }
    }

    private fun onTrackDetected(track: Track) {
        currentTrack = track
        LiveState.update { it.copy(track = track) }
        val data = app.store.data.value
        val setup = SetupOptimizer.optimize(data.garage, track)
        // O plano de pneus só sai na tela de pneus, com as voltas e faixas reais do jogo (nada de chute aqui).
        val msg = buildString {
            append("📍 ${track.name} — ${track.type.label}\n")
            if (setup.changes.isNotEmpty()) {
                append("Setup:\n"); setup.changes.take(4).forEach { append("• ").append(it).append('\n') }
            } else append("Setup atual já é o melhor que você tem.\n")
            com.leandro.f1clashcoach.core.engine.TrackPlaybook.forTrack(data.races, track.id)?.let { tp ->
                append("\nSeu histórico aqui: ${tp.wins}/${tp.races} vitórias")
                tp.bestStrategy?.let { bs -> append(" • melhor: $bs (${tp.bestStrategyWins}/${tp.bestStrategyRaces})") }
            }
        }
        overlay.showMessage(msg.trim(), pin = false)
        LiveState.event("Pista detectada: ${track.name}")
    }

    private fun trackTyres(drivers: List<Pair<Int, com.leandro.f1clashcoach.core.engine.DriverLive>>, lap: Int?) {
        val track = currentTrack ?: return
        lap ?: return
        for ((i, d) in drivers) {
            val tyre = d.tyre ?: continue
            val life = d.tyreLife ?: continue
            val s = stints[i]
            if (s == null || s.tyre != tyre || life > s.lastLife + 15) {
                s?.let { flushStint(track, it) }
                stints[i] = StintTrack(tyre, lap, life, lap, life)
                usedTyres += tyre
            } else if (lap >= s.lastLap) {
                s.lastLap = lap; s.lastLife = life
            }
        }
    }

    private fun flushStint(track: Track, s: StintTrack) {
        val laps = s.lastLap - s.startLap
        val wear = s.startLife - s.lastLife
        if (laps >= 1 && wear in 1..100) {
            app.store.addTyreObservation(TyreObservation(track.id, s.tyre, laps.toDouble(), wear.toDouble(), DrivingPace.NEUTRAL, System.currentTimeMillis()))
        }
    }

    private fun onResult(p: ParsedScreen, autoRecord: Boolean) {
        val now = System.currentTimeMillis()
        currentTrack?.let { t -> stints.values.forEach { flushStint(t, it) } }
        stints.clear()
        val duel = p.game?.duel
        val won = duel?.won ?: p.won
        duel?.flagsAfter?.let { f -> app.store.update { it.copy(flags = f) } }
        if (!autoRecord || won == null || now - lastResultAt < 90_000 || now - lastRaceSeenAt > 10 * 60_000) return
        lastResultAt = now
        val record = RaceRecord(
            id = UUID.randomUUID().toString(), timestamp = now, trackId = currentTrack?.id ?: "desconhecida",
            series = p.series ?: app.store.data.value.garage.series,
            gridPositions = lastGrid, finishPositions = p.positions.take(2), won = won, weather = lastWeather,
            strategy = usedTyres.joinToString("→") { it.short }, source = "auto",
            myScore = app.store.data.value.garage.teamScore.takeIf { it > 0 }, rivalScore = lastRivalScore,
            notes = duel?.let { dd -> "Pontos ${dd.myPoints ?: "?"} x ${dd.rivalPoints ?: "?"}" + if (dd.fastestLap) " • volta mais rápida" else "" } ?: "",
        )
        app.store.addRace(record)
        lastRivalScore = null
        newRace()
        if (won == false) reviewRace(record, duel)
        usedTyres.clear(); lastGrid = emptyList()
        val stars = app.store.data.value.let { d -> com.leandro.f1clashcoach.core.model.SeriesCatalog.merged(d.series).firstOrNull { it.number == d.garage.series }?.prizeStars }
        val txt = if (won == true) "🏆 Vitória registrada!" + (stars?.let { " +★$it para o clube" } ?: "") + (duel?.flagsAfter?.let { "\n🏳 Bandeiras: $it" } ?: "") else debrief(record)
        overlay.showMessage(txt, pin = false)
        LiveState.event("Resultado salvo: ${if (won == true) "vitória" else "derrota"} em ${currentTrack?.name ?: "?"}")
    }

    /** Diagnóstico rápido pós-derrota, sem internet. */
    private fun debrief(r: RaceRecord): String {
        val grid = r.gridPositions.takeIf { it.isNotEmpty() }?.average()
        val fin = r.finishPositions.takeIf { it.isNotEmpty() }?.average()
        val why = when {
            grid != null && fin != null && fin > grid + 0.5 ->
                "Você largou melhor do que chegou: perdeu posições na corrida. Revise pneus/paradas (aba Corrida) e use energia nas retas para defender."
            grid != null && grid > 5 ->
                "Largou atrás (média P${"%.0f".format(grid)}): o problema é a classificação. Priorize Qualifying nas peças e pilotos."
            r.weather != Weather.DRY -> "Corrida com chuva: pare para o intermediário assim que a chuva chegar."
            else -> "Confira na aba Evoluir os upgrades prioritários para esta série."
        }
        val streak = app.store.data.value.races.sortedByDescending { it.timestamp }.takeWhile { it.won == false }.size
        return "Derrota registrada.\n$why" + if (streak >= 3) "\n🛑 $streak derrotas seguidas: pare a liga e evolua antes." else ""
    }

    private fun scanGarage(p: ParsedScreen) {
        val name = p.itemName ?: return
        val changed = when {
            p.carStats != null && p.slot != null -> app.store.mergeScannedPart(name, p.slot!!, p.carStats!!, p.level, p.cards)
            p.driverStats != null -> app.store.mergeScannedDriver(name, p.driverStats!!, p.level, p.cards)
            else -> false
        }
        if (changed && name != lastScanName) {
            lastScanName = name
            overlay.showMessage("✅ Garagem: $name atualizado", pin = false)
            LiveState.event("Garagem atualizada: $name")
        }
    }

    @Volatile private var lastRivalScore: Int? = null
    @Volatile private var lastWinPct: Int? = null
    @Volatile private var lastRanges: Map<Tyre, IntRange> = emptyMap()
    private val liveWear = com.leandro.f1clashcoach.core.engine.LiveWear()

    /** Começo de outra corrida: nada da anterior (voltas, faixas, desgaste) pode vazar. */
    private fun newRace() {
        lastTotalLaps = null
        liveWear.reset()
        tyreHistory.clear()
    }
    private var storeNotified = ""

    /** Aba Membros lida: avalia quem excluir e avisa (painel + notificação, 1x por dia por pessoa). */
    private fun onClubMembers() {
        val d = app.store.data.value
        val now = System.currentTimeMillis()
        val rep = com.leandro.f1clashcoach.core.engine.ClubManager.evaluate(d.club, d.garage.playerName, now)
        if (rep.kicks.isEmpty()) {
            overlay.showMessage("👥 Clube: ${rep.summary.first()}\n✅ Ninguém para excluir agora.", pin = false)
            return
        }
        overlay.showMessage("👥 Clube • quem excluir\n" + rep.kicks.take(5).joinToString("\n") { k ->
            (if (k.urgency == 2) "🔴 " else if (k.urgency == 1) "🟡 " else "⚪ ") + "${k.name} (${k.role}): ${k.reason}"
        } + "\n⏰ ${rep.whenToAct}", pin = false)
        val day = java.time.LocalDate.now().toString()
        val fresh = rep.kicks.filter { it.urgency >= 1 && d.club.notified[it.name] != day }
        if (fresh.isNotEmpty()) {
            com.leandro.f1clashcoach.notify.Notifier.show(app, 4200, "👥 Excluir do clube: ${fresh.size}",
                fresh.take(5).joinToString("\n") { "${it.name}: ${it.reason}" } + "\n" + rep.whenToAct)
            app.store.update { it.copy(club = it.club.copy(notified = it.club.notified + fresh.associate { k -> k.name to day })) }
        }
    }

    /** Derrota: a IA explica o que perdeu e o que mudar (sozinha, sem você pedir). */
    private fun reviewRace(r: RaceRecord, duel: com.leandro.f1clashcoach.core.engine.DuelResult?) {
        if (!com.leandro.f1clashcoach.ai.AiClient.hasAnyKey(app)) return
        val hud = lastRaceHud
        val trackName = currentTrack?.name ?: r.trackId
        scope.launch {
            runCatching {
                val d = app.store.data.value
                val ctx = CoachPrompts.accountContext(d.garage, d.races, currentTrack, d.events, System.currentTimeMillis(), d.objectives, d.gp, com.leandro.f1clashcoach.core.model.SeriesCatalog.merged(d.series), d.flags)
                val facts = buildString {
                    append("Corrida perdida em $trackName, série ${r.series}. ")
                    append("Pneus usados: ${r.strategy.ifBlank { "?" }}. Clima: ${r.weather.label}. ")
                    r.myScore?.let { append("Minha pontuação $it x rival ${r.rivalScore ?: "?"}. ") }
                    duel?.let { append("Placar ${it.myPoints ?: "?"} x ${it.rivalPoints ?: "?"}. ") }
                    if (r.gridPositions.isNotEmpty()) append("Largada ${r.gridPositions}, chegada ${r.finishPositions}. ")
                    hud?.let { h -> append("Último painel: " + h.drivers.joinToString { x -> "${x.code ?: "?"} ${x.tyre?.short ?: "?"} ${x.tyreLife ?: "?"}%" }) }
                }
                com.leandro.f1clashcoach.ai.AiHub(app).ask(AiRequest(CoachPrompts.system(), CoachPrompts.withContext(
                    "Analise esta derrota em até 5 linhas curtas: o que mais pesou (largada, pneu, pit, chuva, pontuação) e 2 mudanças concretas para a próxima. $facts", ctx)))
            }.onSuccess { a ->
                app.store.setRaceReview(r.id, a.text)
                app.store.addChat(ChatMessage(false, "🔎 Análise da derrota em $trackName:\n${a.text}"))
                overlay.showMessage("🔎 Por que perdeu:\n${a.text}", pin = false)
                com.leandro.f1clashcoach.notify.Notifier.show(app, 4300, "🔎 Análise da última derrota", a.text.take(300))
            }.onFailure { Diag.log("Análise pós-corrida falhou: ${it.message}") }
        }
    }

    fun askAi() {
        val frame = LiveState.lastFrame
        val s = app.settings.state.value
        overlay.showMessage("🤖 Analisando a tela…")
        scope.launch {
            val text = try {
                val advisor = com.leandro.f1clashcoach.ai.AiHub(app)
                val d = app.store.data.value
                val ctx = CoachPrompts.accountContext(d.garage, d.races, currentTrack, d.events, System.currentTimeMillis(), d.objectives, d.gp, com.leandro.f1clashcoach.core.model.SeriesCatalog.merged(d.series), d.flags)
                val prompt = CoachPrompts.withContext(
                    CoachPrompts.ANALYZE_SCREENSHOT + " Estou no meio do jogo: responda em no máximo 6 linhas curtas.", ctx,
                )
                val img = frame?.let { AiImage(ImageUtils.toJpeg(it), "image/jpeg") }
                val answer = advisor.ask(AiRequest(CoachPrompts.system(), prompt, image = img))
                app.store.addChat(ChatMessage(true, "[Print do jogo pelo overlay] ${currentTrack?.name ?: ""}"))
                app.store.addChat(ChatMessage(false, answer.text))
                answer.text
            } catch (e: Exception) {
                "⚠️ ${e.message}"
            }
            overlay.showMessage(text)
        }
    }
}
