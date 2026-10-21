package com.leandro.f1clashcoach.capture

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.core.content.IntentCompat
import com.leandro.f1clashcoach.CoachApp
import com.leandro.f1clashcoach.core.ai.AiFactory
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
import com.leandro.f1clashcoach.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Serviço em primeiro plano que lê a tela (MediaProjection + OCR local),
 * mostra dicas no overlay e aprende com as corridas.
 */
class CoachCaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var loopJob: Job? = null
    private lateinit var ocr: OcrEngine
    private lateinit var overlay: OverlayController

    private val app get() = application as CoachApp

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ocr = OcrEngine()
        overlay = OverlayController(this, onAskAi = { askAi() }, onStop = { stopSelf() })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf(); return START_NOT_STICKY
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        if (projection != null) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (data == null) {
            stopSelf(); return START_NOT_STICKY
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = runCatching { mpm.getMediaProjection(code, data) }.getOrNull()
        if (mp == null) {
            stopSelf(); return START_NOT_STICKY
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
            override fun onCapturedContentResize(width: Int, height: Int) { main.post { resize(width, height) } }
        }, main)

        val (w, h) = captureSize()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        display = mp.createVirtualDisplay(
            "F1CoachCapture", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, main,
        )

        overlay.show(app.settings.state.value.overlayAlpha)
        LiveState.update { it.copy(running = true) }
        LiveState.event("Coach iniciado")
        loopJob = scope.launch { readLoop() }
        return START_NOT_STICKY
    }

    /** Tamanho da captura: mesma orientação da tela (o F1 Clash roda em pé), lado maior até ~1600px. */
    private fun captureSize(srcW: Int? = null, srcH: Int? = null): Pair<Int, Int> {
        val (rw, rh) = if (srcW != null && srcH != null) srcW to srcH else {
            val wm = getSystemService(WindowManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = wm.currentWindowMetrics.bounds; b.width() to b.height()
            } else {
                val m = resources.displayMetrics; m.widthPixels to m.heightPixels
            }
        }
        val scale = minOf(1f, 1600f / maxOf(rw, rh))
        return (rw * scale).toInt().coerceAtLeast(2) to (rh * scale).toInt().coerceAtLeast(2)
    }

    private fun resize(width: Int, height: Int) {
        val d = display ?: return
        val (w, h) = captureSize(width, height)
        val old = reader
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        d.resize(w, h, resources.displayMetrics.densityDpi)
        d.surface = r.surface
        old?.close()
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.readLoop() {
        while (isActive) {
            delay(app.settings.state.value.ocrIntervalMs)
            val image = runCatching { reader?.acquireLatestImage() }.getOrNull() ?: continue
            val bitmap = try { ImageUtils.imageToBitmap(image) } catch (e: Exception) { null } finally { image.close() }
            if (bitmap == null) continue
            LiveState.lastFrame = bitmap
            try {
                val lines = ocr.read(bitmap)
                val parsed = com.leandro.f1clashcoach.core.engine.ScreenReader.parse(lines, lastSlot)
                handle(parsed)
                LiveState.update { it.copy(framesRead = it.framesRead + 1, lastParsed = parsed, screen = parsed.screen) }
            } catch (e: Exception) {
                Log.w(TAG, "OCR falhou", e)
            }
        }
    }

    private fun handle(p: ParsedScreen) {
        val data = app.store.data.value
        val settings = app.settings.state.value
        p.track?.let { if (it != currentTrack) onTrackDetected(it) }
        p.totalLaps?.let { lastTotalLaps = it }
        p.weather?.let { lastWeather = it }

        when (p.screen) {
            GameScreen.RACE -> {
                lastRaceSeenAt = System.currentTimeMillis()
                if (p.lap == 1 && p.positions.isNotEmpty()) lastGrid = p.positions
                val snapshot = p.toSnapshot()
                val tm = data.garage.drivers.filter { it.equipped }.map { it.stats.tyreManagement }.ifEmpty { listOf(50) }.average().toInt()
                val tips = LiveCoach(WearModel(data.tyreObservations)).advise(snapshot, currentTrack, tm)
                LiveState.update { it.copy(tips = tips, track = currentTrack) }
                overlay.setHeader("🏁 V${p.lap ?: "?"}/${p.totalLaps ?: lastTotalLaps ?: "?"} • ${currentTrack?.name ?: "pista ?"}")
                overlay.showTips(tips)
                if (settings.learnTyreWear) trackTyres(snapshot.drivers.mapIndexed { i, d -> i to d }, p.lap)
            }
            GameScreen.RACE_RESULT -> onResult(p, settings.autoRecordResults)
            GameScreen.PARTS, GameScreen.DRIVERS, GameScreen.UPGRADE -> if (settings.autoScanGarage) scanGarage(p)
            GameScreen.HOME, GameScreen.RACE_MENU, GameScreen.GP, GameScreen.OBJECTIVES, GameScreen.TEAM_DRIVERS,
            GameScreen.TEAM_CARS, GameScreen.BOOSTERS, GameScreen.CLUB, GameScreen.PIT_PASS, GameScreen.STORE -> onGameScreen(p)
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
        if (ev.aiAdvice.isBlank() && s.activeKey.isNotBlank() && now - lastAutoAiAt > 90_000) {
            lastAutoAiAt = now
            val frame = LiveState.lastFrame ?: return
            scope.launch {
                runCatching {
                    val d = app.store.data.value
                    val advisor = AiFactory.create(s.provider, s.activeKey, s.activeModel)
                    val ctx = CoachPrompts.accountContext(d.garage, d.races, currentTrack, d.events, System.currentTimeMillis(), d.objectives, d.gp)
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
        if (!autoRecord || p.won == null || now - lastResultAt < 90_000 || now - lastRaceSeenAt > 10 * 60_000) return
        lastResultAt = now
        val record = RaceRecord(
            id = UUID.randomUUID().toString(), timestamp = now, trackId = currentTrack?.id ?: "desconhecida",
            series = p.series ?: app.store.data.value.garage.series,
            gridPositions = lastGrid, finishPositions = p.positions.take(2), won = p.won, weather = lastWeather,
            strategy = usedTyres.joinToString("→") { it.short }, source = "auto",
        )
        app.store.addRace(record)
        usedTyres.clear(); lastGrid = emptyList()
        val txt = if (p.won == true) "🏆 Vitória registrada!" else debrief(record)
        overlay.showMessage(txt, pin = false)
        LiveState.event("Resultado salvo: ${if (p.won == true) "vitória" else "derrota"} em ${currentTrack?.name ?: "?"}")
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

    private fun askAi() {
        val frame = LiveState.lastFrame
        val s = app.settings.state.value
        overlay.showMessage("🤖 Analisando a tela…")
        scope.launch {
            val text = try {
                val advisor = AiFactory.create(s.provider, s.activeKey, s.activeModel)
                val d = app.store.data.value
                val ctx = CoachPrompts.accountContext(d.garage, d.races, currentTrack, d.events, System.currentTimeMillis(), d.objectives, d.gp)
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

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, CoachCaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CoachApp.CHANNEL_COACH)
            .setContentTitle("F1 Clash Coach ativo")
            .setContentText("Lendo a tela para te dar dicas em tempo real")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Parar", stop).build())
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        overlay.hide()
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        runCatching { ocr.close() }
        projection = null
        LiveState.update { it.copy(running = false, tips = emptyList()) }
        LiveState.event("Coach parado")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CoachCapture"
        private const val NOTIF_ID = 42
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.leandro.f1clashcoach.STOP"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, CoachCaptureService::class.java).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CoachCaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
