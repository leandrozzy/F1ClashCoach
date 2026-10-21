package com.leandro.f1clashcoach.ai

import android.content.Context
import android.util.Base64
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// Portado do OSM Coach (mesmo autor): Gemini com várias chaves e modelo automático,
// Groq/Mistral/OpenRouter no formato OpenAI e Claude, com reservas, cotas e teste.

object ModelPicker {
    private data class Cand(val id: String, val stable: Boolean, val version: Double, val lite: Boolean)

    /**
     * Ordem aprendida: quem respondeu por último vai para a frente (lista "winners", mais recente primeiro);
     * o resto mantém a ordem base. Só reordena itens que existem na base.
     */
    /** Modelo listado pelo provedor: visão e gratuidade quando a API informa (OpenRouter e Mistral informam). */
    data class ModelInfo(val id: String, val vision: Boolean? = null, val free: Boolean? = null)

    fun parseModels(body: String): List<ModelInfo> {
        val arr = JSONObject(body).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<ModelInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            var vision: Boolean? = null
            val mods = o.optJSONObject("architecture")?.optJSONArray("input_modalities")
            if (mods != null) vision = (0 until mods.length()).any { mods.optString(it) == "image" }
            val caps = o.optJSONObject("capabilities")
            if (caps != null && caps.has("vision")) vision = caps.optBoolean("vision")
            var free: Boolean? = null
            val price = o.optJSONObject("pricing")
            if (price != null) free = (price.optString("prompt").toDoubleOrNull() ?: 1.0) == 0.0 && (price.optString("completion").toDoubleOrNull() ?: 1.0) == 0.0
            if (id.endsWith(":free")) free = true
            out.add(ModelInfo(id, vision, free))
        }
        return out
    }

    /** No OpenRouter só valem os modelos gratuitos (a chave sem crédito não paga os outros). */
    private fun usable(models: List<ModelInfo>, base: String): List<ModelInfo> =
        if (base.contains("openrouter")) models.filter { it.free == true } else models

    /** Modelos com visão a tentar, do melhor para o pior: marcados pela API, de nome conhecido e os conhecidos do provedor. */
    fun compatVisionCandidates(models: List<ModelInfo>, base: String): List<String> {
        val use = usable(models, base)
        val flagged = use.filter { it.vision == true }.map { it.id }
        val unknown = use.filter { it.vision == null }.map { it.id }
        val ids = models.map { it.id }.toSet()
        val known = knownVision(base).filter { models.isEmpty() || it in ids }
        return (rankCompatVision(flagged) + flagged + rankCompatVision(unknown) + known).distinct()
    }

    fun pickCompatText(models: List<ModelInfo>, base: String): String? = pickCompat(usable(models, base).map { it.id })

    fun defaultText(base: String): String = when {
        base.contains("mistral.ai") -> "mistral-small-latest"
        base.contains("openrouter") -> "meta-llama/llama-3.3-70b-instruct:free"
        else -> "openai/gpt-oss-120b"
    }

    fun learnedOrder(base: List<String>, winners: List<String>): List<String> {
        val first = winners.filter { it in base }.distinct()
        return first + base.filter { it !in first }
    }

    /** Escolhe o Flash estável mais novo (sem lite/preview/imagem/áudio) entre os modelos que a chave enxerga. */
    fun pickGemini(names: List<String>): String? = rankGemini(names).firstOrNull()

    /** Lista ordenada dos melhores Flash estáveis (para tentar o próximo quando um estiver sobrecarregado). */
    fun rankGemini(names: List<String>): List<String> {
        val banned = listOf("image", "tts", "live", "audio", "embedding", "robotics", "computer", "exp", "thinking", "8b")
        val cands = names.map { it.removePrefix("models/") }
            .filter { n -> n.startsWith("gemini") && n.contains("flash") && banned.none { n.contains(it) } }
            .map { n ->
                Cand(
                    n,
                    !n.contains("preview") && !n.contains("latest"),
                    Regex("gemini-(\\d+(?:\\.\\d+)?)").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0,
                    n.contains("lite")
                )
            }
        return cands.sortedWith(
            compareByDescending<Cand> { it.stable }.thenByDescending { !it.lite }.thenByDescending { it.version }
        ).map { it.id }
    }

    /** Modelo com visão (aceita imagem) entre os que o provedor lista; null se não houver. */
    fun pickCompatVision(ids: List<String>): String? = rankCompatVision(ids).firstOrNull()

    /** Todos os modelos com visão que o provedor lista, do melhor para o pior. */
    fun rankCompatVision(ids: List<String>): List<String> {
        val prefs = listOf(
            "llama-4-maverick", "llama-4-scout", "llama-4", "gpt-4.1", "gpt-4o", "gpt-5", "grok-4", "vision", "pixtral",
            "mistral-medium", "mistral-small", "-vl", "qwen3.9", "qwen3.8", "qwen3.7", "qwen3.6", "qwen3.5", "gemma-4", "gemma-3", "llava"
        )
        val bad = listOf("guard", "embed", "tts", "whisper", "audio", "image-gen", "dall-e", "realtime", "transcribe")
        val ok = ids.filter { id -> bad.none { id.contains(it, ignoreCase = true) } }
        val out = ArrayList<String>()
        for (p in prefs) for (id in ok) if (id.contains(p, ignoreCase = true) && id !in out) out.add(id)
        return out
    }

    /**
     * Modelos com visão conhecidos de cada provedor compatível, para quando a lista de modelos falha ou vem
     * sem nenhum nome reconhecido (o app testa um a um e guarda o que responder).
     */
    fun knownVision(base: String): List<String> {
        val b = base.lowercase()
        return when {
            // o Groq tirou o Llama 4 do plano gratuito (jun/2026); o Qwen 3.x de 27B lê imagem
            b.contains("groq.com") -> listOf("qwen/qwen3.6-27b", "qwen/qwen3.8-27b", "meta-llama/llama-4-scout-17b-16e-instruct", "meta-llama/llama-4-maverick-17b-128e-instruct")
            b.contains("mistral.ai") -> listOf("mistral-small-latest", "mistral-medium-latest", "pixtral-12b-2409")
            b.contains("x.ai") -> listOf("grok-4", "grok-2-vision-1212", "grok-2-vision-latest")
            b.contains("openrouter") -> listOf("google/gemma-3-27b-it:free", "meta-llama/llama-4-maverick:free", "qwen/qwen2.5-vl-72b-instruct:free", "mistralai/mistral-small-3.2-24b-instruct:free")
            b.contains("openai.com") -> listOf("gpt-4.1-mini", "gpt-4o-mini")
            else -> emptyList()
        }
    }

    /** Modelos do plano gratuito do Gemini (cada um com cota própria): entram como reserva dos descobertos. */
    val GEMINI_FALLBACK = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash", "gemini-2.0-flash-lite")

    /** Descobertos (até 4 + 1 lite) seguidos das reservas do plano gratuito, sem repetir. */
    fun geminiCandidates(discovered: List<String>): List<String> {
        val ranked = rankGemini(discovered)
        val top = ranked.filter { !it.contains("lite") }.take(4) + ranked.filter { it.contains("lite") }.take(1)
        return (top + GEMINI_FALLBACK).distinct()
    }

    /** Escolhe um modelo de texto grande entre os que o provedor (Groq/xAI/OpenAI-compatível) lista. */
    fun pickCompat(ids: List<String>): String? {
        val bad = listOf("whisper", "tts", "guard", "embed", "playai", "orpheus", "distil", "moderation", "image", "ocr", "codestral", "devstral", "voxtral", "saba")
        val ok = ids.filter { id -> bad.none { id.contains(it, ignoreCase = true) } }
        // modelo que "pensa" antes (melhor para tática) primeiro; os demais como reserva
        val prefs = listOf(
            "gpt-oss-120b", "qwen3", "llama-3.3-70b", "llama-3.1-70b", "gpt-oss-20b", "deepseek", "qwen", "grok",
            "mistral-medium-latest", "mistral-small-latest", "mistral-large-latest", "mistral-medium", "mistral-small", "gemma-3-27b", "llama-3.1-8b", "llama"
        )
        for (p in prefs) {
            val hit = ok.firstOrNull { it.contains(p, ignoreCase = true) }
            if (hit != null) return hit
        }
        return ok.firstOrNull()
    }
}

object AiClient {
    data class Reply(val ok: Boolean, val text: String?, val error: String?, val by: String? = null)

    private class HttpResult(val code: Int, val body: String)

    private val gate = Mutex()
    private var lastGeminiAt = 0L
    // Intervalo curto por padrão; só desacelera (7 s) por 10 min depois que o Gemini responder 429.
    @Volatile private var slowUntil = 0L
    private val cooldownUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun cool(provider: String, ms: Long) {
        cooldownUntil[provider] = System.currentTimeMillis() + ms
    }
    private const val CONNECT_MS = 10000
    private const val READ_MS = 45000

    private fun request(method: String, urlStr: String, headers: Map<String, String>, body: String?): HttpResult {
        val c = URL(urlStr).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = CONNECT_MS
            c.readTimeout = READ_MS
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return HttpResult(code, txt)
        } finally {
            c.disconnect()
        }
    }

    /** Só a mensagem do erro da API (o corpo inteiro em JSON é ilegível na tela). */
    private fun short(s: String): String {
        val msg = try {
            JSONObject(s).optJSONObject("error")?.optString("message")?.ifBlank { null }
        } catch (e: Exception) {
            null
        }
        return (msg ?: s).replace("\n", " ").replace(Regex("\\s+"), " ").take(140)
    }

    // ---------------------------------------------------------------- cotas do Gemini por chave e modelo

    private fun blockPrefs(ctx: Context) = ctx.getSharedPreferences("ai_block", Context.MODE_PRIVATE)

    /** Rótulo curto e não secreto da chave (só os 4 últimos caracteres), para a tela e o diagnóstico. */
    fun keyLabel(key: String): String = "…" + key.takeLast(4)

    private fun keyHash(key: String): String = Integer.toHexString(key.hashCode())

    private val PACIFIC: java.util.TimeZone = java.util.TimeZone.getTimeZone("America/Los_Angeles")

    /** A cota gratuita do Gemini renova à meia-noite do horário do Pacífico (não à meia-noite daqui). */
    fun quotaDay(now: Long = System.currentTimeMillis()): String {
        val f = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        f.timeZone = PACIFIC
        return f.format(java.util.Date(now))
    }

    /** Próxima renovação da cota (meia-noite do Pacífico). */
    fun quotaResetAt(now: Long = System.currentTimeMillis()): Long {
        val c = java.util.Calendar.getInstance(PACIFIC)
        c.timeInMillis = now
        c.add(java.util.Calendar.DAY_OF_MONTH, 1)
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun resetText(): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(quotaResetAt()))

    /** Chave+modelo com a cota DIÁRIA esgotada (ou chave recusada, ou modelo que não existe): não é tentado de novo. */
    private fun blockedToday(ctx: Context, key: String, model: String): Boolean {
        val p = blockPrefs(ctx)
        val day = quotaDay()
        if (p.getString(keyHash(key) + "|" + model, "") == day) return true
        if (p.getString(keyHash(key) + "|*", "") == day) return true
        return gone(ctx, model)
    }

    private fun gone(ctx: Context, model: String): Boolean =
        (blockPrefs(ctx).getString("gone|$model", "")?.toLongOrNull() ?: 0L) > System.currentTimeMillis()

    private fun blockForToday(ctx: Context, key: String, model: String) {
        blockPrefs(ctx).edit().putString(keyHash(key) + "|" + model, quotaDay()).apply()
    }

    /** Chave recusada pelo Google (inválida, sem permissão, projeto sem a API): fora até a cota renovar. */
    private fun blockKeyForToday(ctx: Context, key: String) {
        blockPrefs(ctx).edit().putString(keyHash(key) + "|*", quotaDay()).apply()
    }

    /** Modelo que não existe mais (404): fora da lista por 7 dias. */
    private fun markGone(ctx: Context, model: String) {
        blockPrefs(ctx).edit().putString("gone|$model", (System.currentTimeMillis() + 7L * 86400000L).toString()).apply()
    }

    /** Modelo sobrecarregado (503) ou lento: vai para o fim da fila por alguns minutos. */
    private val busyUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 429 por cota diária (ou plano sem cota) x limite por minuto (passa esperando alguns segundos). */
    private fun dailyQuota(body: String): Boolean {
        val b = body.lowercase()
        return b.contains("perday") || b.contains("per day") || b.contains("limit: 0") ||
            (b.contains("exceeded your current quota") && !b.contains("perminute"))
    }

    /** "retryDelay": "17s" no corpo do 429 por minuto. */
    private fun retryDelayMs(body: String): Long? =
        Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)(?:\\.\\d+)?s\"").find(body)?.groupValues?.get(1)?.toLongOrNull()?.times(1000L)

    private fun today(): String = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())

    fun usedToday(ctx: Context): Int {
        val p = ctx.getSharedPreferences("ai_usage", Context.MODE_PRIVATE)
        return if (p.getString("day", "") == today()) p.getInt("count", 0) else 0
    }

    /** Plano gratuito do Gemini: respeita intervalo mínimo e teto diário (o teto vale por chave). */
    private suspend fun throttleGemini(ctx: Context, keys: Int): String? = gate.withLock {
        val p = ctx.getSharedPreferences("ai_usage", Context.MODE_PRIVATE)
        val used = if (p.getString("day", "") == today()) p.getInt("count", 0) else 0
        val cap = (AiKeys.get(ctx, AiKeys.DAILY_CAP, "80").toIntOrNull() ?: 80) * keys.coerceAtLeast(1)
        if (used >= cap) return@withLock "teto diário de $cap chamadas ao Gemini atingido (Ajustes → Teto diário)"
        val gap = if (System.currentTimeMillis() < slowUntil) 7000L else 1200L
        val wait = gap - (System.currentTimeMillis() - lastGeminiAt)
        if (wait > 0) delay(wait)
        lastGeminiAt = System.currentTimeMillis()
        p.edit().putString("day", today()).putInt("count", used + 1).apply()
        null
    }

    /** Modelos que serão tentados, sem consultar a rede (para saber se ainda há cota hoje). */
    private fun cachedGeminiModels(ctx: Context): List<String> {
        val setting = AiKeys.get(ctx, AiKeys.GEMINI_MODEL, "auto")
        val fixed = if (setting.isNotBlank() && !setting.equals("auto", true)) listOf(setting) else emptyList()
        val cached = AiKeys.get(ctx, AiKeys.GEMINI_RESOLVED, "").split(",").filter { it.isNotBlank() }
        return (fixed + cached + ModelPicker.GEMINI_FALLBACK).distinct()
    }

    /** Ainda há alguma chave+modelo do Gemini com cota hoje? (sem gastar chamada) */
    fun geminiAvailable(ctx: Context): Boolean {
        val keys = AiKeys.geminiKeys(ctx)
        if (keys.isEmpty()) return false
        return cachedGeminiModels(ctx).any { m -> keys.any { k -> !blockedToday(ctx, k, m) } }
    }

    private fun exhaustedMsg(ctx: Context): String {
        val n = AiKeys.geminiKeys(ctx).size
        return "cota gratuita de hoje esgotada em todos os modelos d" + (if (n > 1) "as $n chaves" else "a chave") +
            " (renova às ${resetText()}). Para continuar hoje, cole em Ajustes a chave de OUTRO projeto do Google AI Studio " +
            "(separada por vírgula) ou uma chave do Groq."
    }

    /** Situação do Gemini para a tela de Ajustes. */
    fun geminiStatus(ctx: Context): String {
        val keys = AiKeys.geminiKeys(ctx)
        if (keys.isEmpty()) return "sem chave"
        val models = cachedGeminiModels(ctx)
        val pairs = models.size * keys.size
        val free = models.sumOf { m -> keys.count { k -> !blockedToday(ctx, k, m) } }
        val busy = models.filter { (busyUntil[it] ?: 0L) > System.currentTimeMillis() }
        return "${keys.size} chave(s) (${keys.joinToString { keyLabel(it) }}) • $free de $pairs combinações chave/modelo com cota hoje" +
            (if (free < pairs) " • a cota renova às ${resetText()}" else "") +
            (if (busy.isNotEmpty()) " • sobrecarregado agora: ${busy.joinToString()}" else "")
    }

    /** Linhas para o diagnóstico: bloqueios gravados (sem as chaves). */
    fun blockDump(ctx: Context): JSONObject {
        val out = JSONObject()
        for ((k, v) in blockPrefs(ctx).all) out.put(k, v.toString())
        out.put("quotaDay", quotaDay())
        out.put("resetAt", quotaResetAt())
        return out
    }

    // ---------------------------------------------------------------- descoberta de modelos

    private fun listGemini(key: String): List<String> {
        val r = request("GET", "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", mapOf("x-goog-api-key" to key), null)
        if (r.code !in 200..299) return emptyList()
        val arr = JSONObject(r.body).optJSONArray("models") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val methods = o.optJSONArray("supportedGenerationMethods")
            var gen = methods == null
            if (methods != null) for (j in 0 until methods.length()) if (methods.optString(j) == "generateContent") gen = true
            if (gen) out.add(o.optString("name"))
        }
        return out
    }

    private fun listCompat(base: String, key: String): List<ModelPicker.ModelInfo> {
        val r = request("GET", "$base/models", mapOf("Authorization" to "Bearer $key"), null)
        if (r.code !in 200..299) return emptyList()
        return ModelPicker.parseModels(r.body)
    }

    /** Modelo fixo dos Ajustes (se houver) + descobertos pela chave + reservas do plano gratuito. */
    private suspend fun geminiModels(ctx: Context, key: String, forceDiscover: Boolean): List<String> {
        val setting = AiKeys.get(ctx, AiKeys.GEMINI_MODEL, "auto")
        val fixed = if (setting.isNotBlank() && !setting.equals("auto", true)) listOf(setting) else emptyList()
        val cached = AiKeys.get(ctx, AiKeys.GEMINI_RESOLVED, "").split(",").filter { it.isNotBlank() }
        val found = if (cached.isEmpty() || forceDiscover) {
            val names = withContext(Dispatchers.IO) { try { listGemini(key) } catch (e: Exception) { emptyList<String>() } }
            if (names.isNotEmpty()) ModelPicker.geminiCandidates(names).also { AiKeys.put(ctx, AiKeys.GEMINI_RESOLVED, it.joinToString(",")) }
            else cached
        } else cached
        return (fixed + found + ModelPicker.GEMINI_FALLBACK).distinct()
    }

    /** Pares chave+modelo com cota: o melhor modelo em todas as chaves primeiro; sobrecarregados no fim. */
    private fun geminiPairs(ctx: Context, keys: List<String>, models: List<String>): List<Pair<String, String>> {
        val now = System.currentTimeMillis()
        val pairs = ArrayList<Pair<String, String>>()
        for (m in models) for (k in keys) if (!blockedToday(ctx, k, m)) pairs.add(Pair(k, m))
        return pairs.filter { (busyUntil[it.second] ?: 0L) <= now } + pairs.filter { (busyUntil[it.second] ?: 0L) > now }
    }

    /** Modelo de texto do provedor: o fixo dos Ajustes (só Groq), o já descoberto ou o melhor que a chave lista. */
    private suspend fun compatModel(ctx: Context, p: Compat, base: String, key: String, forceDiscover: Boolean): String {
        val setting = p.modelPref?.let { AiKeys.get(ctx, it, "auto") } ?: "auto"
        if (setting.isNotBlank() && !setting.equals("auto", true) && !forceDiscover) return setting
        val cached = AiKeys.get(ctx, p.resolvedPref, "")
        if (cached.isNotBlank() && !forceDiscover) return cached
        val picked = ModelPicker.pickCompatText(compatModels(base, key, forceDiscover), base)
        if (picked != null) AiKeys.put(ctx, p.resolvedPref, picked)
        return picked ?: cached.ifBlank { ModelPicker.defaultText(base) }
    }

    // ---------------------------------------------------------------- provedores

    private suspend fun gemini(ctx: Context, prompt: String, jpeg: ByteArray?, deadline: Long, json: Boolean = false): Reply = withContext(Dispatchers.IO) {
        val keys = AiKeys.geminiKeys(ctx)
        if (keys.isEmpty()) return@withContext Reply(false, null, "chave do Gemini não configurada")
        var models = geminiModels(ctx, keys.first(), false)
        var pairs = geminiPairs(ctx, keys, models)
        if (pairs.isEmpty()) return@withContext Reply(false, null, exhaustedMsg(ctx))
        val capErr = throttleGemini(ctx, keys.size)
        if (capErr != null) return@withContext Reply(false, null, capErr)
        var rediscovered = false
        var waited = false
        var withThinking = true
        var lastErr = "falha desconhecida"
        var idx = 0
        var attempts = 0
        while (idx < pairs.size && attempts < 12) {
            if (System.currentTimeMillis() > deadline) {
                lastErr = "tempo total esgotado ($lastErr)"
                break
            }
            val (key, model) = pairs[idx]
            // a chave pode ter sido recusada numa tentativa anterior desta mesma chamada
            if (blockedToday(ctx, key, model)) {
                idx++
                continue
            }
            attempts++
            Diag.aiCalls++
            val tag = if (keys.size > 1) "$model, chave ${keyLabel(key)}" else model
            AiStatus.set("Gemini ($tag) — tentativa ${idx + 1} de ${pairs.size}")
            val parts = JSONArray().put(JSONObject().put("text", prompt))
            if (jpeg != null) {
                parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
            }
            val gen = JSONObject().put("temperature", if (json) 0.1 else 0.5).put("maxOutputTokens", if (json) 2048 else 4096)
            if (json) gen.put("responseMimeType", "application/json")
            if (withThinking) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts))).put("generationConfig", gen).toString()
            try {
                val r = request("POST", "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", mapOf("x-goog-api-key" to key), body)
                if (r.code in 200..299) {
                    val partsOut = JSONObject(r.body).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    val t = partsOut?.let { arr -> (0 until arr.length()).joinToString("") { arr.optJSONObject(it)?.optString("text") ?: "" } }
                    if (!t.isNullOrBlank()) {
                        busyUntil.remove(model)
                        // Este modelo respondeu: na próxima chamada ele é o primeiro a ser tentado.
                        // (a ordem guarda todos os modelos, inclusive os sem cota hoje: amanhã eles voltam)
                        if (models.firstOrNull() != model) AiKeys.put(ctx, AiKeys.GEMINI_RESOLVED, ModelPicker.learnedOrder(models, listOf(model)).joinToString(","))
                        return@withContext Reply(true, t, null)
                    }
                    lastErr = "$tag: resposta vazia"
                } else {
                    lastErr = "$tag: HTTP ${r.code} " + short(r.body)
                    val b = r.body.lowercase()
                    when {
                        r.code == 429 && dailyQuota(r.body) -> {
                            blockForToday(ctx, key, model)
                            lastErr = "$tag: cota diária esgotada"
                        }
                        r.code == 429 -> {
                            slowUntil = System.currentTimeMillis() + 600000L
                            val wait = retryDelayMs(r.body)
                            if (!waited && wait != null && wait <= 20000L && System.currentTimeMillis() + wait < deadline) {
                                waited = true
                                AiStatus.set("Gemini ($tag): limite por minuto, aguardando ${wait / 1000}s…")
                                delay(wait + 500L)
                                continue
                            }
                        }
                        // 400 "invalid argument": alguns modelos (ex. *-lite novos) recusam o thinkingConfig. Tenta sem ele.
                        r.code == 400 && withThinking && (b.contains("thinking") || b.contains("invalid argument")) -> {
                            withThinking = false
                            continue
                        }
                        r.code == 401 || r.code == 403 || b.contains("api_key_invalid") || b.contains("api key not valid") -> {
                            blockKeyForToday(ctx, key)
                            lastErr = "chave ${keyLabel(key)} recusada pelo Google (inválida ou sem permissão)"
                        }
                        r.code == 404 || (r.code == 400 && b.contains("model") && (b.contains("not found") || b.contains("not supported"))) -> {
                            markGone(ctx, model)
                            if (!rediscovered) {
                                rediscovered = true
                                models = geminiModels(ctx, keys.first(), true)
                                pairs = geminiPairs(ctx, keys, models)
                                idx = 0
                                continue
                            }
                        }
                        r.code >= 500 -> {
                            busyUntil[model] = System.currentTimeMillis() + 300000L
                            lastErr = "$tag: sobrecarregado no Google (HTTP ${r.code})"
                        }
                    }
                }
            } catch (e: SocketTimeoutException) {
                busyUntil[model] = System.currentTimeMillis() + 180000L
                lastErr = "$tag: tempo esgotado (${READ_MS / 1000}s)"
            } catch (e: Exception) {
                lastErr = "$tag: " + (e.message ?: e.javaClass.simpleName)
            }
            idx++
        }
        if (!geminiAvailable(ctx)) Reply(false, null, exhaustedMsg(ctx)) else Reply(false, null, lastErr)
    }

    /** Claude pelo SDK oficial da Anthropic (inclui busca na web quando pedida). */
    private suspend fun claude(ctx: Context, prompt: String, jpeg: ByteArray?, web: Boolean = false): Reply {
        val key = AiKeys.get(ctx, AiKeys.CLAUDE_KEY)
        if (key.isBlank()) return Reply(false, null, "chave do Claude não configurada")
        val model = AiKeys.get(ctx, AiKeys.CLAUDE_MODEL, AiKeys.DEFAULT_CLAUDE_MODEL).ifBlank { AiKeys.DEFAULT_CLAUDE_MODEL }
        AiStatus.set("Claude ($model)")
        Diag.aiCalls++
        return try {
            val a = com.leandro.f1clashcoach.core.ai.ClaudeAdvisor(key, model).ask(
                com.leandro.f1clashcoach.core.ai.AiRequest("", prompt, image = jpeg?.let { com.leandro.f1clashcoach.core.ai.AiImage(it, "image/jpeg") }, webSearch = web)
            )
            if (a.text.isBlank()) Reply(false, null, "resposta vazia") else Reply(true, a.text, null, "Claude $model")
        } catch (e: Exception) {
            Reply(false, null, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Provedores no formato OpenAI (chat/completions): Groq (URL editável, serve também para xAI), Mistral e
     * OpenRouter. Cada um com a sua chave e os seus modelos descobertos.
     */
    class Compat(
        val id: String, val label: String, val keyPref: String, private val fixedBase: String?,
        val modelPref: String?, val resolvedPref: String, val visionPref: String
    ) {
        fun base(ctx: Context): String = (fixedBase ?: AiKeys.get(ctx, AiKeys.GROQ_BASE, AiKeys.DEFAULT_GROQ_BASE)).trim().trimEnd('/')
        fun key(ctx: Context): String = AiKeys.get(ctx, keyPref, "")
    }

    val COMPATS = listOf(
        Compat("compat", "Groq", AiKeys.GROQ_KEY, null, AiKeys.GROQ_MODEL, AiKeys.GROQ_RESOLVED, AiKeys.GROQ_VISION),
        Compat("mistral", "Mistral", AiKeys.MISTRAL_KEY, AiKeys.MISTRAL_BASE, null, "mistral_model_resolved", "mistral_vision_resolved"),
        Compat("openrouter", "OpenRouter", AiKeys.OPENROUTER_KEY, AiKeys.OPENROUTER_BASE, null, "openrouter_model_resolved", "openrouter_vision_resolved")
    )

    fun compatOf(id: String): Compat? = COMPATS.firstOrNull { it.id == id }

    /** Lista de modelos de cada provedor guardada por 12 h (evita uma consulta a cada imagem). */
    private val compatLists = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<ModelPicker.ModelInfo>>>()

    private suspend fun compatModels(base: String, key: String, force: Boolean = false): List<ModelPicker.ModelInfo> {
        val now = System.currentTimeMillis()
        val ck = base + "|" + key.takeLast(6)
        if (!force) compatLists[ck]?.takeIf { now - it.first < 12L * 3600000L }?.let { return it.second }
        val list = withContext(Dispatchers.IO) { try { listCompat(base, key) } catch (e: Exception) { emptyList() } }
        if (list.isNotEmpty()) compatLists[ck] = Pair(now, list)
        return list
    }

    /**
     * Modelos com visão a tentar: o que já respondeu antes, os marcados com visão pelo provedor, os de nome
     * conhecido e os conhecidos do provedor. Uma lista que falhou NÃO vira "sem visão".
     */
    private suspend fun compatVisionModels(ctx: Context, p: Compat, base: String, key: String): List<String> {
        val cached = AiKeys.get(ctx, p.visionPref, "").takeIf { it.isNotBlank() && !it.startsWith("none") }
        val listed = compatModels(base, key)
        return (listOfNotNull(cached) + ModelPicker.compatVisionCandidates(listed, base)).distinct().take(5)
    }

    private fun compatBody(model: String, prompt: String, jpeg: ByteArray?, base: String, json: Boolean = false): String {
        val messages = JSONArray()
        if (jpeg != null) {
            val content = JSONArray()
            content.put(JSONObject().put("type", "text").put("text", prompt))
            val url = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
            // Mistral recebe a imagem como texto direto; os demais no formato da OpenAI ({"url": ...})
            val img: Any = if (base.contains("mistral.ai")) url else JSONObject().put("url", url)
            content.put(JSONObject().put("type", "image_url").put("image_url", img))
            messages.put(JSONObject().put("role", "user").put("content", content))
        } else {
            messages.put(JSONObject().put("role", "user").put("content", prompt))
        }
        val req = JSONObject().put("model", model).put("temperature", 0.2).put("messages", messages)
        // modelos gratuitos do OpenRouter nem sempre aceitam o modo JSON: o texto já pede só JSON
        if (json && jpeg == null && !base.contains("openrouter")) req.put("response_format", JSONObject().put("type", "json_object"))
        return req.toString()
    }

    private fun compatHeaders(base: String, key: String): Map<String, String> =
        if (base.contains("openrouter")) mapOf("Authorization" to "Bearer $key", "X-Title" to "F1 Clash Coach")
        else mapOf("Authorization" to "Bearer $key")

    private suspend fun compat(ctx: Context, p: Compat, prompt: String, jpeg: ByteArray?, deadline: Long): Reply = withContext(Dispatchers.IO) {
        val key = p.key(ctx)
        if (key.isBlank()) return@withContext Reply(false, null, "chave do ${p.label} não configurada")
        val base = p.base(ctx)
        val headers = compatHeaders(base, key)
        if (jpeg != null) {
            val models = compatVisionModels(ctx, p, base, key)
            if (models.isEmpty()) {
                return@withContext Reply(false, null, "o ${p.label} não oferece hoje nenhum modelo que leia imagem para esta chave")
            }
            var lastErr = "falha desconhecida"
            for (model in models) {
                if (System.currentTimeMillis() > deadline) break
                AiStatus.set("${p.label} ($model) com imagem")
                Diag.aiCalls++
                try {
                    val r = request("POST", "$base/chat/completions", headers, compatBody(model, prompt, jpeg, base))
                    if (r.code in 200..299) {
                        val t = JSONObject(r.body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                        if (!t.isNullOrBlank()) {
                            AiKeys.put(ctx, p.visionPref, model)
                            return@withContext Reply(true, t, null)
                        }
                        lastErr = "$model: resposta vazia"
                    } else {
                        lastErr = "$model: HTTP ${r.code} " + short(r.body)
                        // modelo que não existe / não aceita imagem: esquece o guardado e tenta o próximo
                        if ((r.code == 404 || r.code == 400) && AiKeys.get(ctx, p.visionPref, "") == model) {
                            AiKeys.put(ctx, p.visionPref, "")
                        }
                        if (r.code == 401 || r.code == 403) {
                            lastErr = "chave do ${p.label} recusada (HTTP ${r.code})"
                            break
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    lastErr = "$model: tempo esgotado (${READ_MS / 1000}s)"
                } catch (e: Exception) {
                    lastErr = "$model: " + (e.message ?: e.javaClass.simpleName)
                }
            }
            return@withContext Reply(false, null, lastErr)
        }
        var model = compatModel(ctx, p, base, key, false)
        var rediscovered = false
        var lastErr = "falha desconhecida"
        for (attempt in 1..2) {
            if (System.currentTimeMillis() > deadline) break
            AiStatus.set("${p.label} ($model)")
            Diag.aiCalls++
            try {
                val r = request("POST", "$base/chat/completions", headers, compatBody(model, prompt, null, base))
                if (r.code in 200..299) {
                    val t = JSONObject(r.body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                    return@withContext if (t.isNullOrBlank()) Reply(false, null, "resposta vazia") else Reply(true, t, null)
                }
                lastErr = "$model: HTTP ${r.code} " + short(r.body)
                if (r.code == 401 || r.code == 403) return@withContext Reply(false, null, "chave do ${p.label} recusada (HTTP ${r.code})")
                if ((r.code == 404 || r.code == 400) && !rediscovered) {
                    rediscovered = true
                    model = compatModel(ctx, p, base, key, true)
                    continue
                }
                if (r.code != 429 && r.code < 500) break
            } catch (e: SocketTimeoutException) {
                lastErr = "tempo esgotado (${READ_MS / 1000}s)"
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
            }
            if (attempt == 1) delay(3000L)
        }
        Reply(false, null, lastErr)
    }

    private fun learnPrefs(ctx: Context) = ctx.getSharedPreferences("ai_learn", Context.MODE_PRIVATE)

    private fun winners(ctx: Context, hasImage: Boolean): List<String> =
        (learnPrefs(ctx).getString(if (hasImage) "win_img" else "win_txt", "") ?: "").split(",").filter { it.isNotBlank() }

    /** Quem respondeu sobe para o topo; quem falhou sai da lista de vencedores (imagem e texto aprendem separado). */
    private fun learn(ctx: Context, hasImage: Boolean, provider: String, ok: Boolean) {
        val cur = winners(ctx, hasImage).filter { it != provider }
        val next = if (ok) listOf(provider) + cur else cur
        learnPrefs(ctx).edit().putString(if (hasImage) "win_img" else "win_txt", next.joinToString(",")).apply()
    }

    /**
     * Ordem: o último provedor que respondeu vem primeiro (aprendido), depois o preferido nos Ajustes e o resto.
     * Só entram os que têm chave. Gemini sem cota hoje em nenhuma chave vai para o fim (e nem é chamado).
     */
    /** Todos os provedores, na ordem padrão (gratuitos primeiro; Mistral e Claude só com plano pago). */
    val ALL = listOf("gemini", "openrouter", "compat", "mistral", "claude")

    fun label(p: String): String = when (p) {
        "gemini" -> "Gemini"
        "claude" -> "Claude"
        else -> compatOf(p)?.label ?: "Alternativo"
    }

    fun hasKey(ctx: Context, p: String): Boolean = when (p) {
        "gemini" -> AiKeys.geminiKeys(ctx).isNotEmpty()
        "claude" -> AiKeys.get(ctx, AiKeys.CLAUDE_KEY, "").isNotBlank()
        else -> compatOf(p)?.key(ctx)?.isNotBlank() == true
    }

    fun hasAnyKey(ctx: Context): Boolean = ALL.any { hasKey(ctx, it) }

    // ---------------------------------------------------------------- resultado do último teste

    /** Resultado do teste de um provedor: lê imagem, responde texto, segundos e o erro (se houve). */
    data class TestResult(val image: Boolean, val text: Boolean, val secs: Double, val error: String?, val model: String?, val at: Long)

    private fun testPrefs(ctx: Context) = ctx.getSharedPreferences("ai_test", Context.MODE_PRIVATE)

    fun lastTest(ctx: Context, p: String): TestResult? {
        val raw = testPrefs(ctx).getString(p, null) ?: return null
        return try {
            val j = JSONObject(raw)
            TestResult(j.optBoolean("img"), j.optBoolean("txt"), j.optDouble("secs", 0.0), j.optString("err").ifBlank { null }, j.optString("model").ifBlank { null }, j.optLong("at"))
        } catch (e: Exception) {
            null
        }
    }

    private fun saveTest(ctx: Context, p: String, r: TestResult) {
        val j = JSONObject().put("img", r.image).put("txt", r.text).put("secs", r.secs).put("err", r.error ?: "").put("model", r.model ?: "").put("at", r.at)
        testPrefs(ctx).edit().putString(p, j.toString()).apply()
    }

    /** Busca na internet: quem respondeu no último teste (ou o erro). */
    fun lastWebTest(ctx: Context): Pair<Boolean, String>? {
        val raw = testPrefs(ctx).getString("web", null) ?: return null
        return try { JSONObject(raw).let { Pair(it.optBoolean("ok"), it.optString("text")) } } catch (e: Exception) { null }
    }

    /**
     * Ordem automática a partir do teste: quem leu a imagem (mais rápido primeiro), depois quem só respondeu texto,
     * depois os não testados e por fim os que falharam no teste.
     */
    fun autoOrder(have: List<String>, tests: Map<String, TestResult?>, hasImage: Boolean): List<String> {
        fun score(p: String): Double {
            val t = tests[p] ?: return 2000.0
            val ok = if (hasImage) t.image else t.text || t.image
            return if (ok) t.secs else if (t.text || t.image) 1000.0 + t.secs else 3000.0
        }
        return have.withIndex().sortedWith(compareBy<IndexedValue<String>> { score(it.value) }.thenBy { it.index }).map { it.value }
    }

    /**
     * Ordem: no automático, a do último teste com quem respondeu por último na frente; com uma IA escolhida, ela
     * primeiro e as demais como reserva. Só entram os que têm chave. Gemini sem cota hoje vai para o fim.
     */
    fun providerOrder(ctx: Context, hasImage: Boolean): List<String> {
        val have = ALL.filter { hasKey(ctx, it) }
        val pref = AiKeys.get(ctx, AiKeys.PREFERRED, "auto")
        val auto = autoOrder(have, have.associateWith { lastTest(ctx, it) }, hasImage)
        val ordered = if (pref in have) listOf(pref) + ModelPicker.learnedOrder(auto.filter { it != pref }, winners(ctx, hasImage))
        else ModelPicker.learnedOrder(auto, winners(ctx, hasImage))
        // quem acabou de falhar vai para o fim da fila (evita esperar de novo um provedor sobrecarregado)
        val now = System.currentTimeMillis()
        val geminiOut = "gemini" in ordered && !geminiAvailable(ctx)
        fun late(p: String) = (cooldownUntil[p] ?: 0L) > now || (p == "gemini" && geminiOut)
        return ordered.filter { !late(it) } + ordered.filter { late(it) }
    }

    suspend fun askProvider(ctx: Context, provider: String, prompt: String, jpeg: ByteArray?, deadline: Long = System.currentTimeMillis() + 150000L): Reply =
        when (provider) {
            "gemini" -> gemini(ctx, prompt, jpeg, deadline)
            "claude" -> claude(ctx, prompt, jpeg)
            else -> compat(ctx, compatOf(provider) ?: COMPATS.first(), prompt, jpeg, deadline)
        }

    /** Tenta cada provedor com chave, em ordem, com orçamento total de tempo. Nunca fica preso. */
    suspend fun ask(ctx: Context, prompt: String, jpeg: ByteArray?, budgetMs: Long = 150000L): Reply {
        val deadline = System.currentTimeMillis() + budgetMs
        val order = providerOrder(ctx, jpeg != null)
        if (order.isEmpty()) return Reply(false, null, "Nenhuma chave de IA configurada. Abra Ajustes › Inteligência artificial e cole uma chave gratuita.")
        val errors = ArrayList<String>()
        for (p in order) {
            if (System.currentTimeMillis() > deadline) {
                errors.add("tempo total esgotado")
                break
            }
            // Gemini sem cota em todas as chaves/modelos: não gasta chamada nem tempo, só avisa
            if (p == "gemini" && !geminiAvailable(ctx)) {
                errors.add("Gemini: " + exhaustedMsg(ctx))
                continue
            }
            val started = System.currentTimeMillis()
            val r = askProvider(ctx, p, prompt, jpeg, deadline)
            if (r.ok) {
                cooldownUntil.remove(p)
                learn(ctx, jpeg != null, p, true)
                AiStatus.set("")
                return r.copy(by = r.by ?: label(p))
            }
            learn(ctx, jpeg != null, p, false)
            cool(p, 180000L)
            errors.add(label(p) + " (" + ((System.currentTimeMillis() - started) / 1000) + "s): " + (r.error ?: "erro"))
        }
        AiStatus.set("")
        val all = errors.joinToString(" | ")
        Diag.log("IA falhou: $all")
        return Reply(false, null, all)
    }

    /** Imagem de teste: um número grande que a IA precisa ler (prova que o modelo enxerga imagem de verdade). */
    private fun testImage(n: Int): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(320, 160, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.rgb(20, 40, 70))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 72f
            isFakeBoldText = true
        }
        c.drawText("VOLTA $n", 20f, 105f, paint)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun modelOf(ctx: Context, p: String, image: Boolean): String? = when (p) {
        "gemini" -> AiKeys.get(ctx, AiKeys.GEMINI_RESOLVED, "").substringBefore(",").ifBlank { null }
        "claude" -> AiKeys.get(ctx, AiKeys.CLAUDE_MODEL, AiKeys.DEFAULT_CLAUDE_MODEL)
        else -> compatOf(p)?.let { AiKeys.get(ctx, if (image) it.visionPref else it.resolvedPref, "").takeIf { m -> m.isNotBlank() && !m.startsWith("none") } }
    }

    /**
     * Testa cada provedor com chave: lê uma imagem com um número (o que importa para os prints do jogo), responde texto
     * (tática e Diretor) e, por fim, a busca na internet (táticas da comunidade). Guarda o resultado para a ordem
     * automática e para a tela de Ajustes.
     */
    suspend fun test(ctx: Context): String {
        val have = ALL.filter { hasKey(ctx, it) }
        if (have.isEmpty()) return "Nenhuma chave configurada."
        val lines = ArrayList<String>()
        for (p in have) {
            if (p == "gemini" && !geminiAvailable(ctx)) {
                val msg = exhaustedMsg(ctx)
                saveTest(ctx, p, TestResult(false, false, 0.0, msg, null, System.currentTimeMillis()))
                lines.add("✘ Gemini: $msg")
                continue
            }
            val n = 40 + (System.currentTimeMillis() % 50).toInt()
            AiStatus.set("Testando ${label(p)}: leitura de imagem…")
            val t0 = System.currentTimeMillis()
            val ri = askProvider(ctx, p, "Leia o número escrito na imagem. Responda somente com o JSON {\"n\": numero}", testImage(n), t0 + 60000L)
            val secs = (System.currentTimeMillis() - t0) / 100 / 10.0
            val imgOk = ri.ok && (ri.text ?: "").contains(n.toString())
            var txtOk = imgOk
            var err: String? = when {
                imgOk -> null
                ri.ok -> "respondeu, mas não leu o número da imagem"
                else -> ri.error
            }
            if (!imgOk) {
                AiStatus.set("Testando ${label(p)}: texto…")
                val rt = askProvider(ctx, p, "Responda somente com o JSON {\"ok\": true}", null, System.currentTimeMillis() + 45000L)
                txtOk = rt.ok
                if (!rt.ok) err = (err ?: "") + (if (err != null) " • texto: " else "") + (rt.error ?: "erro")
            }
            val model = modelOf(ctx, p, imgOk)
            saveTest(ctx, p, TestResult(imgOk, txtOk, secs, err, model, System.currentTimeMillis()))
            val m = model?.let { " ($it)" } ?: ""
            lines.add(
                when {
                    imgOk -> "✔ ${label(p)}$m: lê imagem e texto em ${secs}s"
                    txtOk -> "◐ ${label(p)}$m: só texto (não leu a imagem: ${err ?: "?"})"
                    else -> "✘ ${label(p)}$m: ${err ?: "erro"}"
                }
            )
        }
        AiStatus.set("Testando a busca na internet…")
        val w = askGrounded(ctx, "Pesquise na internet e responda em uma linha: qual empresa desenvolve o jogo F1 Clash?")
        val webText = if (w.ok) "✔ Busca na internet: ${w.by ?: "ok"}" else "✘ Busca na internet: ${w.error ?: "erro"}"
        testPrefs(ctx).edit().putString("web", JSONObject().put("ok", w.ok).put("text", webText).put("at", System.currentTimeMillis()).toString()).apply()
        lines.add(webText)
        if ("gemini" in have) lines.add("Gemini: " + geminiStatus(ctx))
        AiStatus.set("")
        return lines.joinToString("\n")
    }

    /** Pergunta ao Gemini com busca no Google (fontes atuais da internet). Devolve o texto (com JSON no meio). */
    suspend fun askGrounded(ctx: Context, prompt: String): Reply = withContext(Dispatchers.IO) {
        val errs = ArrayList<String>()
        val keys = AiKeys.geminiKeys(ctx)
        if (keys.isNotEmpty()) {
            // modelos com busca no Google no plano gratuito (a busca tem cota própria, separada da normal)
            val models = (geminiModels(ctx, keys.first(), false).filter { !it.contains("lite") } + ModelPicker.GEMINI_FALLBACK).distinct()
            val g = groundedGemini(ctx, keys, prompt, models)
            if (g.ok) return@withContext g
            errs.add("Gemini: " + g.error)
        }
        // Groq: o modelo "compound" pesquisa na web sozinho
        val ck = AiKeys.get(ctx, AiKeys.GROQ_KEY, "")
        val base = AiKeys.get(ctx, AiKeys.GROQ_BASE, AiKeys.DEFAULT_GROQ_BASE).trimEnd('/')
        if (ck.isNotBlank() && base.contains("groq.com")) {
            // gpt-oss no Groq com a ferramenta de busca no navegador (a chave gratuita costuma ter acesso)
            for (model in listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b")) {
                AiStatus.set("Pesquisando a web (Groq $model + busca)…")
                Diag.aiCalls++
                try {
                    val body = JSONObject().put("model", model).put("temperature", 0.2)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                        .put("tools", JSONArray().put(JSONObject().put("type", "browser_search"))).toString()
                    val r = request("POST", "$base/chat/completions", mapOf("Authorization" to "Bearer $ck"), body)
                    if (r.code in 200..299) {
                        val t = JSONObject(r.body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                        if (!t.isNullOrBlank()) {
                            AiStatus.set("")
                            return@withContext Reply(true, t, null, "Groq $model + busca")
                        }
                        errs.add("Groq $model (busca): resposta vazia")
                    } else errs.add("Groq $model (busca): HTTP ${r.code} " + short(r.body))
                } catch (e: Exception) {
                    errs.add("Groq $model (busca): " + (e.message ?: e.javaClass.simpleName))
                }
            }
            for (model in listOf("groq/compound", "groq/compound-mini")) {
                AiStatus.set("Pesquisando a web (Groq $model)…")
                Diag.aiCalls++
                try {
                    val body = JSONObject().put("model", model).put("temperature", 0.2)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt))).toString()
                    val r = request("POST", "$base/chat/completions", mapOf("Authorization" to "Bearer $ck"), body)
                    if (r.code in 200..299) {
                        val t = JSONObject(r.body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                        if (!t.isNullOrBlank()) {
                            AiStatus.set("")
                            return@withContext Reply(true, t, null, "Groq $model")
                        }
                        errs.add("Groq $model: resposta vazia")
                    } else errs.add("Groq $model: HTTP ${r.code} " + short(r.body))
                } catch (e: Exception) {
                    errs.add("Groq $model: " + (e.message ?: e.javaClass.simpleName))
                }
            }
        }
        if (AiKeys.get(ctx, AiKeys.CLAUDE_KEY).isNotBlank()) {
            val c = claude(ctx, prompt, null, web = true)
            if (c.ok) return@withContext c.copy(by = (c.by ?: "Claude") + " + busca")
            errs.add("Claude (busca): " + c.error)
        }
        AiStatus.set("")
        Reply(false, null, errs.joinToString(" | ").ifBlank { "nenhuma IA com busca na internet configurada" })
    }

    private fun groundedGemini(ctx: Context, keys: List<String>, prompt: String, models: List<String>): Reply {
        var lastErr = "falha desconhecida"
        var attempts = 0
        for (model in models) for (key in keys) {
            if (attempts >= 6) return Reply(false, null, lastErr)
            // a busca tem cota diária própria: bloqueia só "busca" daquele modelo naquela chave
            if (blockedToday(ctx, key, "search:$model") || gone(ctx, model)) continue
            attempts++
            Diag.aiCalls++
            val tag = if (keys.size > 1) "$model, chave ${keyLabel(key)}" else model
            AiStatus.set("Pesquisando a web ($tag)…")
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                .put("generationConfig", JSONObject().put("temperature", 0.2).put("maxOutputTokens", 8192))
                .toString()
            try {
                val r = request("POST", "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", mapOf("x-goog-api-key" to key), body)
                if (r.code in 200..299) {
                    val parts = JSONObject(r.body).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    val sb = StringBuilder()
                    if (parts != null) for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text") ?: "")
                    if (sb.isNotBlank()) {
                        AiStatus.set("")
                        return Reply(true, sb.toString(), null, "Gemini $model + Google")
                    }
                    lastErr = "resposta vazia ($tag)"
                } else {
                    val b = r.body.lowercase()
                    lastErr = when {
                        r.code == 429 -> {
                            if (dailyQuota(r.body)) blockForToday(ctx, key, "search:$model")
                            "cota da busca esgotada ($tag)"
                        }
                        r.code == 401 || r.code == 403 || b.contains("api key not valid") -> {
                            blockKeyForToday(ctx, key)
                            "chave ${keyLabel(key)} recusada"
                        }
                        r.code == 404 -> {
                            markGone(ctx, model)
                            "modelo não existe ($model)"
                        }
                        else -> "HTTP ${r.code} ($tag) " + short(r.body)
                    }
                }
            } catch (e: Exception) {
                lastErr = (e.message ?: e.javaClass.simpleName) + " ($tag)"
            }
        }
        return Reply(false, null, lastErr)
    }

    fun parseJson(raw: String?): JSONObject? {
        if (raw == null) return null
        val s = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        try { return JSONObject(s.substring(a, b + 1)) } catch (e: Exception) { }
        // Texto com mais de um bloco (ex.: resposta da busca com explicação e citações): pega o maior objeto válido.
        var best: JSONObject? = null
        var bestLen = 0
        var i = s.indexOf('{')
        while (i >= 0) {
            var depth = 0
            var inStr = false
            var esc = false
            var end = -1
            for (j in i until s.length) {
                val c = s[j]
                if (inStr) {
                    if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                    continue
                }
                if (c == '"') inStr = true
                else if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        end = j
                        break
                    }
                }
            }
            if (end < 0) break
            if (end - i + 1 > bestLen) {
                val o = try { JSONObject(s.substring(i, end + 1)) } catch (e: Exception) { null }
                if (o != null) {
                    best = o
                    bestLen = end - i + 1
                }
            }
            i = s.indexOf('{', end + 1)
        }
        return best
    }
}

