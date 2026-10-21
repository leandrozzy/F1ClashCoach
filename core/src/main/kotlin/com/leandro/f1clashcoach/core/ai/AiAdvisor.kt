package com.leandro.f1clashcoach.core.ai

enum class AiProvider(val label: String) { CLAUDE("Claude (Anthropic)"), GEMINI("Gemini (Google)") }

data class ChatTurn(val fromUser: Boolean, val text: String)

class AiImage(val bytes: ByteArray, val mimeType: String = "image/jpeg")

data class AiRequest(
    val system: String,
    val prompt: String,
    val history: List<ChatTurn> = emptyList(),
    val image: AiImage? = null,
    /** Permite que a IA pesquise na web (meta atual, eventos, guias). */
    val webSearch: Boolean = false,
)

data class AiSource(val title: String, val url: String)

data class AiAnswer(val text: String, val sources: List<AiSource> = emptyList())

class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface AiAdvisor {
    suspend fun ask(request: AiRequest): AiAnswer
}

object AiFactory {
    fun create(provider: AiProvider, apiKey: String, model: String?): AiAdvisor {
        if (apiKey.isBlank()) throw AiException("Configure a chave da API em Ajustes.")
        return when (provider) {
            AiProvider.CLAUDE -> ClaudeAdvisor(apiKey.trim(), model?.takeIf { it.isNotBlank() } ?: ClaudeAdvisor.DEFAULT_MODEL)
            AiProvider.GEMINI -> GeminiAdvisor(apiKey.trim(), model?.takeIf { it.isNotBlank() } ?: GeminiAdvisor.DEFAULT_MODEL)
        }
    }
}
