package com.leandro.f1clashcoach.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Alternativa gratuita (com limites) via API REST do Gemini, com busca Google integrada. */
class GeminiAdvisor(private val apiKey: String, private val model: String = DEFAULT_MODEL) : AiAdvisor {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.MINUTES)
        .build()

    override suspend fun ask(request: AiRequest): AiAnswer = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", request.system) }) }
            }
            putJsonArray("contents") {
                ClaudeAdvisor.normalizeHistory(request.history).forEach { t ->
                    add(buildJsonObject {
                        put("role", if (t.fromUser) "user" else "model")
                        putJsonArray("parts") { add(buildJsonObject { put("text", t.text) }) }
                    })
                }
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        request.image?.let { img ->
                            add(buildJsonObject {
                                putJsonObject("inline_data") {
                                    put("mime_type", img.mimeType)
                                    put("data", Base64.getEncoder().encodeToString(img.bytes))
                                }
                            })
                        }
                        add(buildJsonObject { put("text", request.prompt) })
                    }
                })
            }
            if (request.webSearch) {
                putJsonArray("tools") { add(buildJsonObject { putJsonObject("google_search") {} }) }
            }
        }

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val raw = try {
            http.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { json.parseToJsonElement(txt).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content }.getOrNull()
                    throw AiException(
                        when (resp.code) {
                            400, 401, 403 -> "Gemini recusou a chave/pedido: ${msg ?: resp.code}"
                            429 -> "Limite gratuito do Gemini atingido. Tente mais tarde."
                            else -> "Erro Gemini ${resp.code}: ${msg ?: ""}"
                        }
                    )
                }
                txt
            }
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            throw AiException("Falha de conexão com o Gemini: ${e.message}", e)
        }
        parse(raw)
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-latest"
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(raw: String): AiAnswer {
            val root = json.parseToJsonElement(raw).jsonObject
            val cand = (root["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
                ?: return AiAnswer("A IA não retornou resposta (talvez bloqueada pelo filtro de conteúdo).")
            val text = (cand["content"] as? JsonObject)?.get("parts")?.jsonArray
                ?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.content }
                ?.joinToString("") .orEmpty()
            val sources = (cand["groundingMetadata"] as? JsonObject)?.get("groundingChunks")?.jsonArray
                ?.mapNotNull { (it as? JsonObject)?.get("web") as? JsonObject }
                ?.mapNotNull { w ->
                    val uri = w["uri"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    AiSource(w["title"]?.jsonPrimitive?.content ?: uri, uri)
                }?.distinctBy { it.url }.orEmpty()
            return AiAnswer(text.trim(), sources)
        }
    }
}
