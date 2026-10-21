package com.leandro.f1clashcoach.ai

import android.content.Context
import com.leandro.f1clashcoach.core.ai.AiAdvisor
import com.leandro.f1clashcoach.core.ai.AiAnswer
import com.leandro.f1clashcoach.core.ai.AiException
import com.leandro.f1clashcoach.core.ai.AiRequest

/**
 * Ponte entre o Coach e as IAs do OSM: junta instruções, contexto da conta e conversa num texto só e tenta
 * Gemini (várias chaves), Groq, OpenRouter, Mistral e Claude, nessa ordem aprendida, até uma responder.
 */
class AiHub(context: Context) : AiAdvisor {
    private val ctx = context.applicationContext

    override suspend fun ask(request: AiRequest): AiAnswer {
        if (!AiClient.hasAnyKey(ctx)) throw AiException(NO_KEY)
        val prompt = buildString {
            if (request.system.isNotBlank()) append(request.system.trim()).append("\n\n")
            val hist = request.history.takeLast(8)
            if (hist.isNotEmpty()) {
                append("CONVERSA ANTERIOR:\n")
                hist.forEach { append(if (it.fromUser) "Jogador: " else "Coach: ").append(it.text.take(1200)).append('\n') }
                append('\n')
            }
            append(request.prompt)
        }
        val jpeg = request.image?.bytes
        var r: AiClient.Reply? = null
        if (request.webSearch && jpeg == null) {
            r = AiClient.askGrounded(ctx, prompt).takeIf { it.ok }
        }
        if (r == null) r = AiClient.ask(ctx, prompt, jpeg)
        if (!r.ok || r.text.isNullOrBlank()) throw AiException("Nenhuma IA respondeu: " + (r.error ?: "erro desconhecido"))
        return AiAnswer(r.text.trim() + (r.by?.let { "\n\n— respondido por $it" } ?: ""))
    }

    companion object {
        const val NO_KEY = "Nenhuma IA configurada. Abra Ajustes › IA e cole uma chave grátis do Gemini ou do Groq."
    }
}
