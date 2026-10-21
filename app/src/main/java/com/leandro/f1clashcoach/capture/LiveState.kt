package com.leandro.f1clashcoach.capture

import android.graphics.Bitmap
import com.leandro.f1clashcoach.core.engine.GameScreen
import com.leandro.f1clashcoach.core.engine.ParsedScreen
import com.leandro.f1clashcoach.core.engine.Tip
import com.leandro.f1clashcoach.core.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LiveStatus(
    val running: Boolean = false,
    val screen: GameScreen = GameScreen.UNKNOWN,
    val track: Track? = null,
    val tips: List<Tip> = emptyList(),
    val lastParsed: ParsedScreen? = null,
    val events: List<String> = emptyList(),
    val framesRead: Long = 0,
    val race: RaceInfo = RaceInfo(),
)

/** O que foi lido da corrida atual/próxima (tudo do próprio jogo). */
data class RaceInfo(
    val trackName: String? = null,
    val laps: Int? = null,
    val lap: Int? = null,
    val ranges: Map<com.leandro.f1clashcoach.core.model.Tyre, IntRange> = emptyMap(),
    val lapTimes: Map<com.leandro.f1clashcoach.core.model.Tyre, Double> = emptyMap(),
    val plans: List<String> = emptyList(),
    val boosterLines: List<String> = emptyList(),
    val pitLines: List<String> = emptyList(),
    val odds: String? = null,
    val updatedAt: Long = 0,
)

/** Estado ao vivo compartilhado entre o serviço de captura, o overlay e as telas do app. */
object LiveState {
    private val _status = MutableStateFlow(LiveStatus())
    val status: StateFlow<LiveStatus> = _status

    @Volatile var lastFrame: Bitmap? = null

    fun update(transform: (LiveStatus) -> LiveStatus) {
        _status.value = transform(_status.value)
    }

    fun event(msg: String) = update { it.copy(events = (listOf(msg) + it.events).take(30)) }
}
