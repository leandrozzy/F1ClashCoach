package com.leandro.f1clashcoach.ai

import android.content.Context

/** Chaves e preferências de IA (mesmo esquema do OSM Coach). Ficam só no aparelho. */
object AiKeys {
    private fun p(ctx: Context) = ctx.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
    fun get(ctx: Context, key: String, def: String = ""): String = p(ctx).getString(key, def) ?: def
    fun put(ctx: Context, key: String, value: String) { p(ctx).edit().putString(key, value.trim()).apply() }

    /** Várias chaves do Gemini separadas por vírgula/espaço/linha: cada projeto tem cota grátis própria. */
    fun geminiKeys(ctx: Context): List<String> = splitKeys(get(ctx, GEMINI_KEY))
    fun splitKeys(raw: String): List<String> = raw.split(Regex("[,;\\s]+")).map { it.trim() }.filter { it.length >= 20 }.distinct()

    const val GEMINI_KEY = "gemini_key"
    const val GEMINI_MODEL = "gemini_model"
    const val GEMINI_RESOLVED = "gemini_model_resolved"
    const val GROQ_KEY = "compat_key"
    const val GROQ_BASE = "compat_base"
    const val GROQ_MODEL = "compat_model"
    const val GROQ_RESOLVED = "compat_model_resolved"
    const val GROQ_VISION = "compat_vision_resolved"
    const val CLAUDE_KEY = "claude_key"
    const val CLAUDE_MODEL = "claude_model"
    const val MISTRAL_KEY = "mistral_key"
    const val MISTRAL_BASE = "https://api.mistral.ai/v1"
    const val OPENROUTER_KEY = "openrouter_key"
    const val OPENROUTER_BASE = "https://openrouter.ai/api/v1"
    const val PREFERRED = "preferred_provider"
    const val DAILY_CAP = "daily_cap"

    const val DEFAULT_GROQ_BASE = "https://api.groq.com/openai/v1"
    const val DEFAULT_CLAUDE_MODEL = "claude-opus-5-5"

    /** Migra a chave do Gemini/Claude da versão anterior do Coach. */
    fun migrateFromOld(ctx: Context) {
        if (get(ctx, "migrated_v1") == "1") return
        val old = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        old.getString("geminiKey", "")?.takeIf { it.isNotBlank() && get(ctx, GEMINI_KEY).isBlank() }?.let { put(ctx, GEMINI_KEY, it) }
        old.getString("claudeKey", "")?.takeIf { it.isNotBlank() && get(ctx, CLAUDE_KEY).isBlank() }?.let { put(ctx, CLAUDE_KEY, it) }
        put(ctx, "migrated_v1", "1")
    }
}

/** Etapa atual da chamada (para a tela mostrar o que está acontecendo). */
object AiStatus {
    @Volatile var stage: String = ""
    fun set(s: String) { stage = s }
}
