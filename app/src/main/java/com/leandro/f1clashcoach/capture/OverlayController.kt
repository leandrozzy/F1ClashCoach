package com.leandro.f1clashcoach.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.leandro.f1clashcoach.core.engine.Tip
import com.leandro.f1clashcoach.core.engine.TipLevel

/**
 * Painel por cima do jogo, feito para NÃO atrapalhar:
 * - fica recolhido numa bolinha 🏁 na borda da tela (arrastável); toque para abrir;
 * - abre sozinho só com mensagem nova fora da corrida e se recolhe em poucos segundos;
 * - na corrida não abre: alertas aparecem numa faixa fina no topo que deixa o toque passar para o jogo;
 * - some por um instante na hora da foto da tela.
 */
class OverlayController(
    private val context: Context,
    private val onAskAi: () -> Unit,
    private val onStop: () -> Unit,
    /** Dentro do serviço de acessibilidade: não precisa da permissão de sobrepor. */
    private val accessibility: Boolean = false,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val dp = context.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private var root: LinearLayout? = null
    private var ticker: TextView? = null
    private lateinit var bubble: TextView
    private lateinit var panel: LinearLayout
    private lateinit var header: TextView
    private lateinit var body: TextView
    private lateinit var scroll: ScrollView
    private var expanded = false
    private var pinned = false
    private var lastTicker = ""
    private var lastMsg = ""
    /** Última vez que chegaram dicas de corrida: durante a corrida o painel nunca abre sozinho. */
    private var raceAt = 0L
    private var shownAlpha = 0.92f

    private val type = if (accessibility) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    // FLAG_SECURE faria a foto da acessibilidade falhar: lá o painel some (alpha 0) na hora da foto.
    private val secure = if (accessibility) 0 else WindowManager.LayoutParams.FLAG_SECURE

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or secure,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = 0; y = (context.resources.displayMetrics.heightPixels * 0.30).toInt()
    }

    /** Faixa de alerta da corrida: não recebe toques (passam direto para o jogo). */
    private val tickerParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or secure,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = px(36)
    }

    private val collapseLater = Runnable { if (!pinned) setExpanded(false) }
    private val hideTicker = Runnable { ticker?.visibility = View.GONE }

    fun canShow(): Boolean = accessibility || Settings.canDrawOverlays(context)

    val isShown: Boolean get() = root != null

    /** Esconde tudo por um instante (foto da tela limpa). Chamar na thread principal. */
    fun setHidden(hidden: Boolean): Boolean {
        val r = root ?: return false
        if (hidden) {
            if (r.alpha == 0f) return false
            r.alpha = 0f; ticker?.alpha = 0f
        } else {
            r.alpha = shownAlpha; ticker?.alpha = 1f
        }
        return true
    }

    private fun round(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = px(radius).toFloat(); setColor(color)
        stroke?.let { setStroke(px(1), it) }
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    fun show(alpha: Float) {
        if (root != null || !canShow()) return
        shownAlpha = alpha.coerceIn(0.5f, 1f)

        bubble = TextView(context).apply {
            text = "🏁"; textSize = 18f; gravity = Gravity.CENTER
            background = round(Color.argb(220, 19, 28, 46), 22, Color.argb(255, 225, 6, 0))
            layoutParams = LinearLayout.LayoutParams(px(44), px(44))
        }
        header = TextView(context).apply {
            text = "F1 Coach"; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; textSize = 13f
            setPadding(px(12), px(8), px(12), px(4))
        }
        body = TextView(context).apply {
            setTextColor(Color.rgb(234, 240, 250)); textSize = 13f; setLineSpacing(0f, 1.1f)
            setPadding(px(12), 0, px(12), px(8))
            text = "Lendo o jogo… Toque na 🏁 para recolher."
        }
        scroll = ScrollView(context).apply {
            addView(body)
            layoutParams = LinearLayout.LayoutParams(px(250), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        fun chip(label: String, action: () -> Unit) = TextView(context).apply {
            text = label; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = round(Color.argb(255, 27, 39, 64), 14)
            setPadding(px(10), px(6), px(10), px(6))
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(px(3), 0, px(3), px(8)) }
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(6), 0, px(6), 0)
            addView(chip("🤖 IA") { onAskAi() })
            addView(chip("Recolher") { pinned = false; setExpanded(false) })
            addView(chip("✕") { onStop() })
        }
        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Color.argb(238, 11, 18, 32), 16, Color.argb(255, 225, 6, 0))
            addView(header); addView(scroll); addView(buttons)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, px(4), 0) }
        }
        root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            this.alpha = shownAlpha
            addView(panel); addView(bubble)
        }

        // Bolinha: toque abre/fecha, arrastar muda de lugar (só na vertical, presa na borda).
        var startY = 0; var touchY = 0f; var moved = false
        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startY = params.y; touchY = e.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - touchY).toInt()
                    if (kotlin.math.abs(dy) > px(6)) moved = true
                    if (moved) { params.y = (startY + dy).coerceAtLeast(0); root?.let { wm.updateViewLayout(it, params) } }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) { pinned = false; setExpanded(!expanded) }; true }
                else -> false
            }
        }
        wm.addView(root, params)

        ticker = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            background = round(Color.argb(215, 11, 18, 32), 14, Color.argb(255, 225, 6, 0))
            setPadding(px(12), px(5), px(12), px(5))
            maxLines = 2
            visibility = View.GONE
        }
        runCatching { wm.addView(ticker, tickerParams) }
    }

    private fun setExpanded(open: Boolean) {
        if (root == null) return
        expanded = open
        panel.visibility = if (open) View.VISIBLE else View.GONE
        bubble.text = if (open) "✕" else "🏁"
        main.removeCallbacks(collapseLater)
    }

    fun setHeader(text: String) { root?.post { header.text = text } }

    /** Dicas da corrida: o painel não abre; o alerta mais importante passa na faixa do topo. */
    fun showTips(tips: List<Tip>, footer: String? = null, ticker: Boolean = true) {
        val top = tips.firstOrNull { it.level == TipLevel.URGENT } ?: tips.firstOrNull { it.level == TipLevel.IMPORTANT }
        raceAt = System.currentTimeMillis()
        main.post {
            if (root == null) return@post
            bubble.background = round(
                when (top?.level) { TipLevel.URGENT -> Color.argb(235, 200, 20, 20); TipLevel.IMPORTANT -> Color.argb(235, 190, 150, 20); else -> Color.argb(220, 19, 28, 46) },
                22, Color.argb(255, 225, 6, 0),
            )
            if (!pinned) body.text = buildString {
                if (tips.isEmpty()) append("Sem alertas. Siga o plano.") else tips.take(5).forEach { t ->
                    append(when (t.level) { TipLevel.URGENT -> "🔴 "; TipLevel.IMPORTANT -> "🟡 "; TipLevel.INFO -> "⚪ " })
                    append(t.title)
                    if (t.detail.isNotBlank() && t.level != TipLevel.INFO) append("\n   ").append(t.detail)
                    append('\n')
                }
                footer?.let { append('\n').append(it) }
            }.trim()
            if (ticker && top != null && top.title != lastTicker) {
                lastTicker = top.title
                ticker?.let { tk ->
                    tk.text = (if (top.level == TipLevel.URGENT) "🔴 " else "🟡 ") + top.title
                    tk.visibility = View.VISIBLE
                    main.removeCallbacks(hideTicker)
                    main.postDelayed(hideTicker, 6000)
                }
            } else if (top == null) lastTicker = ""
        }
    }

    private var raceKey = ""
    private val shownAt = HashMap<String, Long>()

    /**
     * Faixa da corrida (não recebe toques): uma linha por piloto. Só aparece de novo quando a decisão muda
     * ([key]), não a cada leitura, para não piscar o tempo todo.
     */
    fun raceStrip(text: String, key: String, urgent: Boolean) {
        raceAt = System.currentTimeMillis()
        main.post {
            if (root == null || key == raceKey) return@post
            raceKey = key
            ticker?.let { tk ->
                tk.text = text
                tk.background = round(if (urgent) Color.argb(235, 200, 20, 20) else Color.argb(220, 11, 18, 32), 14, Color.argb(255, 225, 6, 0))
                tk.visibility = View.VISIBLE
                main.removeCallbacks(hideTicker); main.postDelayed(hideTicker, if (urgent) 12000 else 7000)
            }
        }
    }

    fun resetRace() { raceKey = "" }

    /**
     * Mensagem nova. [pin] = fica aberta até você recolher (ex.: resposta da IA que você pediu).
     * Sem pin, abre e se recolhe sozinha em ~10 s.
     */
    fun showMessage(text: String, pin: Boolean = true) {
        main.post {
            if (root == null) return@post
            // A mesma mensagem chega a cada leitura da tela: só atualiza o texto, não reabre o painel.
            if (text == lastMsg) { if (!pinned) body.text = text; return@post }
            lastMsg = text
            if (!pin) {
                // Aviso automático: nunca abre o painel por cima do jogo. Passa 1–2 linhas na faixa do
                // topo (não recebe toques) e o texto completo fica na bolinha para quem quiser abrir.
                if (!pinned) body.text = text
                bubble.text = if (expanded) "✕" else "💬"
                // o mesmo aviso não volta a passar na faixa antes de 30 min (ex.: "caixote grátis" em toda tela)
                val now = System.currentTimeMillis()
                val head = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return@post
                if (now - (shownAt[head] ?: 0L) < 30 * 60_000L) return@post
                shownAt[head] = now
                ticker?.let { tk ->
                    tk.background = round(Color.argb(220, 11, 18, 32), 14, Color.argb(255, 225, 6, 0))
                    tk.text = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.take(2).joinToString("\n").take(140)
                    tk.visibility = View.VISIBLE
                    main.removeCallbacks(hideTicker); main.postDelayed(hideTicker, 7000)
                }
                return@post
            }
            pinned = pin
            body.text = text
            scroll.layoutParams = scroll.layoutParams.apply {
                height = if (text.length > 350) (context.resources.displayMetrics.heightPixels * 0.40).toInt() else LinearLayout.LayoutParams.WRAP_CONTENT
            }
            setExpanded(true)
            if (!pin) main.postDelayed(collapseLater, (6000L + text.length * 25L).coerceAtMost(14000L))
        }
    }

    fun hide() {
        main.removeCallbacks(collapseLater); main.removeCallbacks(hideTicker)
        root?.let { runCatching { wm.removeView(it) } }
        ticker?.let { runCatching { wm.removeView(it) } }
        root = null; ticker = null; expanded = false; pinned = false
    }
}
