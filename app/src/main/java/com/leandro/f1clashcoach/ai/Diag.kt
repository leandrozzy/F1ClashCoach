package com.leandro.f1clashcoach.ai

import android.content.Context
import android.os.Build

/**
 * Diagnóstico ao vivo (mesma ideia do OSM Coach): mostra na tela o que o Coach está lendo, quantas fotos já
 * processou, o último erro e o último travamento. Assim dá para ver na hora se a leitura está funcionando.
 */
object Diag {
    @Volatile var aiCalls = 0
    @Volatile var serviceConnected = false
    @Volatile var captureMode = ""
    @Volatile var gameInFront = false
    @Volatile var lastGameEventAt = 0L
    @Volatile var shots = 0
    @Volatile var shotFails = 0
    @Volatile var framesRead = 0
    @Volatile var recognized = 0
    @Volatile var lastScreen = ""
    @Volatile var lastScreenAt = 0L
    @Volatile var lastOcr = ""
    @Volatile var lastOcrLines = 0
    @Volatile var lastOcrMs = 0L
    @Volatile var lastError: String? = null
    @Volatile var lastErrorAt = 0L
    private val logs = ArrayDeque<String>()

    fun error(msg: String) {
        lastError = msg
        lastErrorAt = System.currentTimeMillis()
        log("ERRO: $msg")
    }

    fun log(msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        synchronized(logs) {
            logs.addFirst("$t $msg")
            while (logs.size > 60) logs.removeLast()
        }
    }

    fun recent(n: Int = 20): List<String> = synchronized(logs) { logs.take(n) }

    /** Texto completo para o usuário copiar e mandar quando algo não funcionar. */
    fun report(ctx: Context): String = buildString {
        val v = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        append("F1 Clash Coach $v • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) • ${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("Captura: ${captureMode.ifBlank { "desligada" }} • acessibilidade ${if (serviceConnected) "ligada" else "desligada"} • jogo na frente: $gameInFront\n")
        append("Fotos: $shots (falhas $shotFails) • lidas $framesRead • reconhecidas $recognized • OCR ${lastOcrMs}ms\n")
        append("Última tela: ${lastScreen.ifBlank { "-" }}\n")
        append("IA: $aiCalls chamadas\n")
        lastError?.let { append("Último erro: $it\n") }
        Health.lastCrash(ctx)?.let { append("Último travamento: ${it.second}\n") }
        append("OCR ($lastOcrLines linhas): ${lastOcr.take(600)}\n")
        append("Log:\n").append(recent(25).joinToString("\n"))
    }
}

/** Registra o último travamento do app (horário + causa) para mostrar no diagnóstico. */
object Health {
    private fun p(ctx: Context) = ctx.getSharedPreferences("health", Context.MODE_PRIVATE)
    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val where = e.stackTrace.take(4).joinToString(" < ") { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber }
                p(app).edit()
                    .putLong("crash_at", System.currentTimeMillis())
                    .putString("crash", (e.javaClass.simpleName + ": " + (e.message ?: "")).take(160) + " @ " + where.take(240))
                    .commit()
            } catch (_: Exception) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun lastCrash(ctx: Context): Pair<Long, String>? {
        val at = p(ctx).getLong("crash_at", 0L)
        if (at == 0L) return null
        return Pair(at, p(ctx).getString("crash", "") ?: "")
    }

    fun clearCrash(ctx: Context) { p(ctx).edit().remove("crash_at").remove("crash").apply() }
}
