package com.leandro.f1clashcoach.core.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.WebSearchTool20260209
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.Base64

/** Coach com Claude via SDK oficial da Anthropic. Suporta imagem (print do jogo) e busca na web. */
class ClaudeAdvisor(apiKey: String, private val model: String = DEFAULT_MODEL) : AiAdvisor {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofMinutes(4))
        .maxRetries(2)
        .build()

    override suspend fun ask(request: AiRequest): AiAnswer = withContext(Dispatchers.IO) {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(request.system)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
            // Se o modelo recusar por engano, o servidor tenta outro modelo automaticamente.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))

        // A API exige alternância usuário/assistente começando pelo usuário.
        normalizeHistory(request.history).forEach { t ->
            if (t.fromUser) builder.addUserMessage(t.text) else builder.addAssistantMessage(t.text)
        }

        val blocks = mutableListOf<ContentBlockParam>()
        request.image?.let { img ->
            blocks += ContentBlockParam.ofImage(
                Base64ImageSource.builder()
                    .data(Base64.getEncoder().encodeToString(img.bytes))
                    .mediaType(if (img.mimeType == "image/png") Base64ImageSource.MediaType.IMAGE_PNG else Base64ImageSource.MediaType.IMAGE_JPEG)
                    .build()
            )
        }
        blocks += ContentBlockParam.ofText(request.prompt)
        builder.addUserMessageOfBlockParams(blocks)

        if (request.webSearch) builder.addTool(WebSearchTool20260209.builder().maxUses(6L).build())

        val texts = StringBuilder()
        val sources = LinkedHashMap<String, AiSource>()
        try {
            repeat(MAX_CONTINUATIONS) {
                val response: Message = client.messages().create(builder.build())
                collect(response, texts, sources)
                when (response.stopReason().orElse(null)) {
                    StopReason.PAUSE_TURN -> builder.addMessage(response) // busca longa: continua de onde parou
                    StopReason.REFUSAL -> {
                        val why = response.stopDetails().flatMap { it.explanation() }.orElse("")
                        if (texts.isBlank()) texts.append("A IA não respondeu a esse pedido. $why".trim())
                        return@withContext AiAnswer(texts.toString().trim(), sources.values.toList())
                    }
                    StopReason.MAX_TOKENS -> {
                        texts.append("\n\n(resposta cortada por tamanho)")
                        return@withContext AiAnswer(texts.toString().trim(), sources.values.toList())
                    }
                    else -> return@withContext AiAnswer(texts.toString().trim(), sources.values.toList())
                }
            }
            AiAnswer(texts.toString().trim(), sources.values.toList())
        } catch (e: UnauthorizedException) {
            throw AiException("Chave da Anthropic inválida. Confira em Ajustes.", e)
        } catch (e: RateLimitException) {
            throw AiException("Limite de uso da API atingido. Tente em instantes.", e)
        } catch (e: AnthropicServiceException) {
            throw AiException("Erro da API Claude (${e.statusCode()}): ${e.message}", e)
        } catch (e: Exception) {
            throw AiException("Falha de conexão com a IA: ${e.message}", e)
        }
    }

    private fun collect(response: Message, texts: StringBuilder, sources: MutableMap<String, AiSource>) {
        response.content().forEach { block ->
            block.text().ifPresent { texts.append(it.text()) }
            block.webSearchToolResult().ifPresent { r ->
                r.content().resultBlocks().ifPresent { results ->
                    results.forEach { sources.putIfAbsent(it.url(), AiSource(it.title(), it.url())) }
                }
            }
        }
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        private const val MAX_CONTINUATIONS = 4

        fun normalizeHistory(history: List<ChatTurn>): List<ChatTurn> {
            val out = ArrayList<ChatTurn>()
            for (t in history.dropWhile { !it.fromUser }) {
                if (t.text.isBlank()) continue
                if (out.isNotEmpty() && out.last().fromUser == t.fromUser) {
                    out[out.lastIndex] = ChatTurn(t.fromUser, out.last().text + "\n\n" + t.text)
                } else out += t
            }
            // A mensagem atual é do usuário, então o histórico precisa terminar no assistente.
            if (out.isNotEmpty() && out.last().fromUser) out.removeAt(out.lastIndex)
            return out.takeLast(20).let { if (it.firstOrNull()?.fromUser == false) it.drop(1) else it }
        }
    }
}
