package com.leandro.f1clashcoach.capture

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.leandro.f1clashcoach.CoachApp
import com.leandro.f1clashcoach.ai.Diag
import com.leandro.f1clashcoach.ai.Health
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Leitura do jogo como no OSM Coach: serviço de acessibilidade que tira a foto da tela sozinho (Android 11+)
 * enquanto o F1 Clash está aberto. Não precisa autorizar gravação de tela a cada vez e não para sozinho.
 */
class CoachAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var overlay: OverlayController? = null
    private var processor: FrameProcessor? = null
    @Volatile private var gameInFront = false
    @Volatile private var paused = false

    private val app get() = application as CoachApp

    override fun onServiceConnected() {
        Health.install(applicationContext)
        instance = this
        Diag.serviceConnected = true
        Diag.captureMode = "acessibilidade"
        Diag.log("Leitura por acessibilidade ligada")
        val ov = OverlayController(this, onAskAi = { processor?.askAi() }, onStop = { pause() }, accessibility = true)
        overlay = ov
        processor = FrameProcessor(app, ov, scope)
        LiveState.event("Leitura automática ligada")
        startLoop()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        when {
            pkg == CoachApp.F1_CLASH_PACKAGE -> {
                if (!gameInFront) {
                    Diag.log("F1 Clash aberto")
                    paused = false
                }
                gameInFront = true
                Diag.lastGameEventAt = System.currentTimeMillis()
            }
            // teclado, barra de notificações e o próprio painel não contam como "saiu do jogo"
            pkg == packageName && event.className?.toString()?.contains("MainActivity") != true -> Unit
            pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("keyboard") -> Unit
            else -> {
                if (gameInFront) Diag.log("Saiu do jogo ($pkg)")
                gameInFront = false
            }
        }
        Diag.gameInFront = gameInFront
    }

    private fun pause() {
        paused = true
        overlay?.hide()
        LiveState.update { it.copy(running = false) }
        LiveState.event("Painel fechado — volta quando você abrir o jogo de novo")
    }

    private fun startLoop() {
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                val active = gameInFront && !paused && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                withContext(Dispatchers.Main) {
                    val ov = overlay ?: return@withContext
                    if (active && !ov.isShown) { ov.show(app.settings.state.value.overlayAlpha); ov.setHeader("🏁 F1 Coach • lendo o jogo") }
                    if (!active && ov.isShown && !paused) ov.hide()
                }
                LiveState.update { if (it.running != active) it.copy(running = active) else it }
                if (!active) { delay(1000L); continue }
                try {
                    val hid = withContext(Dispatchers.Main) { overlay?.setHidden(true) == true }
                    if (hid) delay(70L)
                    val bmp = try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) takeShot() else null } finally {
                        if (hid) withContext(Dispatchers.Main) { overlay?.setHidden(false) }
                    }
                    if (bmp != null) {
                        Diag.shots++
                        processor?.onFrame(shrink(bmp))
                    } else Diag.shotFails++
                } catch (e: Exception) {
                    Diag.error("Captura: " + (e.message ?: e.javaClass.simpleName))
                }
                delay(app.settings.state.value.ocrIntervalMs.coerceAtLeast(900L))
            }
        }
    }

    /** Lado maior até 1600px: OCR mais rápido e menos memória. */
    private fun shrink(b: Bitmap): Bitmap {
        val scale = 1600f / maxOf(b.width, b.height)
        if (scale >= 1f) return b
        val s = Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true)
        if (s != b) b.recycle()
        return s
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeShot(): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    buffer.close()
                    if (cont.isActive) cont.resume(copy)
                }

                override fun onFailure(errorCode: Int) {
                    Diag.error("Foto da tela falhou (código $errorCode)")
                    if (cont.isActive) cont.resume(null)
                }
            })
        } catch (e: Exception) {
            Diag.error("Foto da tela: " + (e.message ?: e.javaClass.simpleName))
            if (cont.isActive) cont.resume(null)
        }
    }

    override fun onInterrupt() { Diag.log("Acessibilidade interrompida pelo Android") }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun shutdown() {
        loop?.cancel()
        overlay?.hide()
        processor?.close()
        processor = null
        instance = null
        Diag.serviceConnected = false
        if (Diag.captureMode == "acessibilidade") Diag.captureMode = ""
        LiveState.update { it.copy(running = false) }
    }

    companion object {
        @Volatile var instance: CoachAccessibilityService? = null

        /** A leitura automática só funciona assim no Android 11 ou mais novo. */
        val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        fun isEnabled(ctx: Context): Boolean {
            if (instance != null) return true
            val me = ComponentName(ctx, CoachAccessibilityService::class.java).flattenToString()
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
