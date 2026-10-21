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
import com.leandro.f1clashcoach.ai.Diag
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
    private lateinit var overlay: OverlayController
    private lateinit var processor: FrameProcessor

    private val app get() = application as CoachApp


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlay = OverlayController(this, onAskAi = { processor.askAi() }, onStop = { stopSelf() })
        processor = FrameProcessor(app, overlay, scope)
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
        Diag.captureMode = "gravação de tela"
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
            val bitmap = try { ImageUtils.imageToBitmap(image) } catch (e: Exception) { Diag.error("Imagem: ${e.message}"); null } finally { image.close() }
            if (bitmap == null) continue
            Diag.shots++
            processor.onFrame(bitmap)
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
        processor.close()
        Diag.captureMode = ""
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
