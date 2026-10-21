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
            handle(parsed)
        } catch (e: Exception) {
            Diag.error("Tela ${parsed.screen.label}: " + (e.message ?: e.javaClass.simpleName))
        }
        LiveState.update { it.copy(framesRead = it.framesRead + 1, lastParsed = parsed, screen = parsed.screen) }
        return parsed.screen
    }

    fun close() { runCatching { ocr.close() } }

    private fun handle(p: ParsedScreen) {
        val data = app.store.data.value
        val settings = app.settings.state.value
        p.track?.let { if (it != currentTrack) onTrackDetected(it) }
        p.totalLaps?.let { lastTotalLaps = it }
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
                    val tips = LiveCoach(WearModel(data.tyreObservations)).adviseHud(hud, currentTrack, tm, names)
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
                val advice = items.joinToString("\n\n") { "${it.icon} ${it.title}\n${it.detail}" }
                onEventScreen(GameMode.GRAND_PRIX, listOfNotNull(gp.name, gp.round).joinToString(" — "), gp.timeLeftMs, advice)
            }
            GameScreen.PRE_RACE_TYRES -> if (g.tyreLapTimes.isNotEmpty()) {
                // Tempos reais por composto → plano de pneus para esta corrida.
                val avg = com.leandro.f1clashcoach.core.model.Tyre.entries.mapNotNull { t ->
                    g.tyreLapTimes.values.mapNotNull { it[t] }.takeIf { it.isNotEmpty() }?.average()?.let { t to it }
                }.toMap()
                currentLapTimes = avg
                val track = currentTrack ?: com.leandro.f1clashcoach.core.model.TrackCatalog.byId(d.gp?.trackId)
                val laps = g.totalLaps ?: lastTotalLaps ?: d.series[d.garage.series]?.laps ?: 6
                if (track != null) {
                    val tm = d.garage.drivers.filter { it.equipped }.map { it.stats.tyreManagement }.ifEmpty { listOf(50) }.average().toInt()
                    val pit = d.garage.carTotals?.pitStop?.takeIf { it > 0 } ?: 4.0
                    val plans = runCatching { RaceStrategist(WearModel(d.tyreObservations)).bestPlans(RaceContext(track, laps, tyreManagement = tm, pitStopTime = pit, lapTimes = avg), top = 2) }.getOrDefault(emptyList())
                    overlay.showMessage(
                        "🛞 Largada em ${track.name} ($laps voltas)\n" + plans.joinToString("\n") { pl -> "• ${pl.summary()}" } +
                            "\nTempos: " + avg.entries.joinToString(" ") { "${it.key.short} ${"%.2f".format(it.value)}s" },
                        pin = false,
                    )
                }
            }
            GameScreen.PRE_RACE -> g.rivalTeamScore?.let { rival ->
                val mine = d.garage.teamScore
                val diff = mine - rival
                overlay.showMessage(
                    "🆚 Rival: pontuação $rival • você $mine (${if (diff >= 0) "+" else ""}$diff)\n" +
                        when { diff >= 40 -> "Você é favorito: largue com o plano padrão e controle o ritmo."; diff <= -40 -> "Rival mais forte: arrisque na estratégia (pneu diferente/uma parada a menos) e use o Overtake cedo."; else -> "Duelo equilibrado: classificação e pit stop decidem." },
                    pin = false,
                )
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
            GameScreen.CLUB -> if (g.clubLeagueName != null) app.store.upsertEvent(GameMode.CLUB, "Liga de clubes ${g.clubLeagueName}", g.clubLeagueLeftMs)
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
            GameScreen.STORE -> if (d.garage.featuredAssets.isNotEmpty()) {
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
        val laps = lastTotalLaps ?: 6
        val tm = data.garage.drivers.filter { it.equipped }.map { it.stats.tyreManagement }.ifEmpty { listOf(50) }.average().toInt()
        val pit = data.garage.carTotals?.pitStop?.takeIf { it > 0 } ?: data.garage.parts.filter { it.equipped }.sumOf { it.stats.pitStop }.takeIf { it > 0 } ?: 1.0
        val plan = runCatching {
            RaceStrategist(WearModel(data.tyreObservations)).bestPlans(RaceContext(track, laps, tyreManagement = tm, pitStopTime = pit), top = 1).firstOrNull()
        }.getOrNull()
        val msg = buildString {
            append("📍 ${track.name} — ${track.type.label}\n")
            if (setup.changes.isNotEmpty()) {
                append("Setup:\n"); setup.changes.take(4).forEach { append("• ").append(it).append('\n') }
            } else append("Setup atual já é o melhor que você tem.\n")
            plan?.let { append("Estratégia ($laps v): ").append(it.summary()) }
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
            notes = duel?.let { dd -> "Pontos ${dd.myPoints ?: "?"} x ${dd.rivalPoints ?: "?"}" + if (dd.fastestLap) " • volta mais rápida" else "" } ?: "",
        )
        app.store.addRace(record)
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
