package com.leandro.f1clashcoach.data

import android.content.Context
import com.leandro.f1clashcoach.core.ai.AiProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    val provider: AiProvider = AiProvider.CLAUDE,
    val claudeKey: String = "",
    val claudeModel: String = "",
    val geminiKey: String = "",
    val geminiModel: String = "",
    val ocrIntervalMs: Long = 1200,
    val autoRecordResults: Boolean = true,
    val autoScanGarage: Boolean = true,
    val learnTyreWear: Boolean = true,
    val overlayAlpha: Float = 0.88f,
) {
    val activeKey: String get() = if (provider == AiProvider.CLAUDE) claudeKey else geminiKey
    val activeModel: String get() = if (provider == AiProvider.CLAUDE) claudeModel else geminiModel
}

/** Preferências do app (privadas ao aparelho). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state

    private fun load() = AppSettings(
        provider = runCatching { AiProvider.valueOf(prefs.getString("provider", AiProvider.CLAUDE.name)!!) }.getOrDefault(AiProvider.CLAUDE),
        claudeKey = prefs.getString("claudeKey", "")!!,
        claudeModel = prefs.getString("claudeModel", "")!!,
        geminiKey = prefs.getString("geminiKey", "")!!,
        geminiModel = prefs.getString("geminiModel", "")!!,
        ocrIntervalMs = prefs.getLong("ocrIntervalMs", 1200),
        autoRecordResults = prefs.getBoolean("autoRecord", true),
        autoScanGarage = prefs.getBoolean("autoScan", true),
        learnTyreWear = prefs.getBoolean("learnWear", true),
        overlayAlpha = prefs.getFloat("overlayAlpha", 0.88f),
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_state.value)
        prefs.edit()
            .putString("provider", s.provider.name)
            .putString("claudeKey", s.claudeKey)
            .putString("claudeModel", s.claudeModel)
            .putString("geminiKey", s.geminiKey)
            .putString("geminiModel", s.geminiModel)
            .putLong("ocrIntervalMs", s.ocrIntervalMs)
            .putBoolean("autoRecord", s.autoRecordResults)
            .putBoolean("autoScan", s.autoScanGarage)
            .putBoolean("learnWear", s.learnTyreWear)
            .putFloat("overlayAlpha", s.overlayAlpha)
            .apply()
        _state.value = s
    }
}
