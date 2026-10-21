package com.leandro.f1clashcoach.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.leandro.f1clashcoach.core.engine.Tip
import com.leandro.f1clashcoach.core.engine.TipLevel

/**
 * Painel flutuante por cima do jogo. Usa FLAG_SECURE para não aparecer na própria
 * captura de tela (assim o OCR nunca lê as dicas do Coach como se fossem do jogo).
 */
class OverlayController(
    private val context: Context,
    private val onAskAi: () -> Unit,
    private val onStop: () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private lateinit var header: TextView
    private lateinit var body: TextView
    private lateinit var scroll: ScrollView
    private lateinit var buttons: LinearLayout
    private var collapsed = false
    private var pinnedMessage: String? = null

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 24; y = 120
    }

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    fun show(alpha: Float) {
        if (root != null || !canShow()) return
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        header = TextView(context).apply {
            text = "🏁 F1 Coach"
            setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; textSize = 13f
            setPadding(px(10), px(6), px(10), px(4))
        }
        body = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 12.5f
            setPadding(px(10), 0, px(10), px(6))
            text = "Abra o F1 Clash. Estou lendo a tela…"
        }
        scroll = ScrollView(context).apply {
            addView(body)
            layoutParams = LinearLayout.LayoutParams(px(260), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        fun btn(label: String, action: () -> Unit) = Button(context).apply {
            text = label; textSize = 11f; isAllCaps = false; minHeight = 0; minimumHeight = 0
            setPadding(px(8), px(2), px(8), px(2))
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, px(34), 1f).apply { setMargins(px(2), 0, px(2), px(4)) }
        }
        buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("🤖 IA") { onAskAi() })
            addView(btn("Limpar") { pinnedMessage = null; body.text = "…" })
            addView(btn("—") { toggle() })
            addView(btn("✕") { onStop() })
        }
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = px(14).toFloat(); setColor(Color.argb(235, 15, 17, 21)); setStroke(px(1), Color.argb(255, 225, 6, 0)) }
            this.alpha = alpha
            addView(header); addView(scroll); addView(buttons)
        }

        // Arrastar pelo cabeçalho.
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        header.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - touchX).toInt(); params.y = startY + (e.rawY - touchY).toInt()
                    root?.let { wm.updateViewLayout(it, params) }; true
                }
                MotionEvent.ACTION_UP -> { if (kotlin.math.abs(e.rawX - touchX) < 8 && kotlin.math.abs(e.rawY - touchY) < 8) toggle(); true }
                else -> false
            }
        }
        wm.addView(root, params)
    }

    private fun toggle() {
        collapsed = !collapsed
        scroll.visibility = if (collapsed) View.GONE else View.VISIBLE
        buttons.visibility = if (collapsed) View.GONE else View.VISIBLE
    }

    fun setHeader(text: String) { root?.post { header.text = text } }

    fun showTips(tips: List<Tip>, footer: String? = null) {
        if (pinnedMessage != null) return
        val text = buildString {
            if (tips.isEmpty()) append("Sem alertas. Siga o plano.") else tips.take(5).forEach { t ->
                append(when (t.level) { TipLevel.URGENT -> "🔴 "; TipLevel.IMPORTANT -> "🟡 "; TipLevel.INFO -> "⚪ " })
                append(t.title)
                if (t.detail.isNotBlank() && t.level != TipLevel.INFO) append("\n   ").append(t.detail)
                append('\n')
            }
            footer?.let { append('\n').append(it) }
        }.trim()
        root?.post { body.text = text }
    }

    /** Mensagem fixa (ex.: resposta da IA) até o usuário tocar em Limpar. */
    fun showMessage(text: String, pin: Boolean = true) {
        pinnedMessage = if (pin) text else null
        root?.post {
            body.text = text
            scroll.layoutParams = scroll.layoutParams.apply { height = if (text.length > 400) (context.resources.displayMetrics.heightPixels * 0.55).toInt() else LinearLayout.LayoutParams.WRAP_CONTENT }
            if (collapsed) toggle()
        }
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }
}
