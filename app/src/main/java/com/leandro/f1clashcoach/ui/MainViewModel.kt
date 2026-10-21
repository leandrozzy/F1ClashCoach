package com.leandro.f1clashcoach.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leandro.f1clashcoach.CoachApp
import com.leandro.f1clashcoach.capture.ImageUtils
import com.leandro.f1clashcoach.capture.OcrEngine
import com.leandro.f1clashcoach.core.ai.AiFactory
import com.leandro.f1clashcoach.core.ai.AiImage
import com.leandro.f1clashcoach.core.ai.AiRequest
import com.leandro.f1clashcoach.core.ai.CoachPrompts
import com.leandro.f1clashcoach.core.engine.GameScreen
import com.leandro.f1clashcoach.core.engine.ScreenReader
import com.leandro.f1clashcoach.core.model.RaceRecord
import com.leandro.f1clashcoach.core.model.Track
import com.leandro.f1clashcoach.data.ChatMessage
import com.leandro.f1clashcoach.news.NewsItem
import com.leandro.f1clashcoach.news.NewsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CoachApp
    val store = app.store
    val settings = app.settings

    private val _aiBusy = MutableStateFlow(false)
    val aiBusy: StateFlow<Boolean> = _aiBusy

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    private val _news = MutableStateFlow<List<NewsItem>>(emptyList())
    val news: StateFlow<List<NewsItem>> = _news
    private val _newsErrors = MutableStateFlow<List<String>>(emptyList())
    val newsErrors: StateFlow<List<String>> = _newsErrors
    private val _newsLoading = MutableStateFlow(false)
    val newsLoading: StateFlow<Boolean> = _newsLoading

    /** Imagem anexada aguardando envio no chat (vinda da galeria ou de "compartilhar"). */
    val pendingImage = MutableStateFlow<Uri?>(null)

    fun consumeToast() { _toast.value = null }
    fun toast(msg: String) { _toast.value = msg }

    /** Envia uma pergunta para a IA com o contexto completo da conta. */
    fun ask(question: String, imageUri: Uri? = null, webSearch: Boolean = false, focusTrack: Track? = null) {
        if (question.isBlank() && imageUri == null) return
        if (_aiBusy.value) return
        val q = question.ifBlank { CoachPrompts.ANALYZE_SCREENSHOT }
        store.addChat(ChatMessage(true, q + (if (imageUri != null) "\n📎 print anexado" else "") + (if (webSearch) "\n🌐 com busca na web" else "")))
        _aiBusy.value = true
        viewModelScope.launch {
            try {
                val s = settings.state.value
                val advisor = AiFactory.create(s.provider, s.activeKey, s.activeModel)
                val d = store.data.value
                val history = d.chat.dropLast(1).map { it.toTurn() }
                val image = imageUri?.let { uri ->
                    withContext(Dispatchers.IO) { ImageUtils.loadUri(app, uri)?.let { AiImage(ImageUtils.toJpeg(it), "image/jpeg") } }
                }
                val prompt = CoachPrompts.withContext(q, CoachPrompts.accountContext(d.garage, d.races, focusTrack, d.events))
                val answer = advisor.ask(AiRequest(CoachPrompts.system(), prompt, history, image, webSearch))
                store.addChat(ChatMessage(false, answer.text.ifBlank { "(sem resposta)" }, answer.sources.map { "${it.title}|${it.url}" }))
                if (webSearch && q == CoachPrompts.WEEKLY_META) store.update { it.copy(lastMeta = answer.text, lastMetaTime = System.currentTimeMillis()) }
            } catch (e: Exception) {
                store.addChat(ChatMessage(false, "⚠️ ${e.message}"))
            } finally {
                _aiBusy.value = false
            }
        }
    }

    private val _briefingBusy = MutableStateFlow(false)
    val briefingBusy: StateFlow<Boolean> = _briefingBusy
    private val _briefingError = MutableStateFlow<String?>(null)
    val briefingError: StateFlow<String?> = _briefingError

    /**
     * Briefing automático da IA: roda sozinho 1x por dia (ou quando aparece evento novo),
     * sem o usuário precisar perguntar. [force] = botão "atualizar".
     */
    fun autoBriefing(force: Boolean = false) {
        val s = settings.state.value
        if (s.activeKey.isBlank() || _briefingBusy.value) return
        val d = store.data.value
        val today = LocalDate.now().toString()
        val lastDay = if (d.aiBriefingTime == 0L) "" else java.time.Instant.ofEpochMilli(d.aiBriefingTime).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        val newEvent = d.events.any { it.firstSeen > d.aiBriefingTime }
        if (!force && lastDay == today && !newEvent) return
        _briefingBusy.value = true
        _briefingError.value = null
        viewModelScope.launch {
            try {
                val advisor = AiFactory.create(s.provider, s.activeKey, s.activeModel)
                val prompt = CoachPrompts.withContext(CoachPrompts.DAILY_BRIEFING, CoachPrompts.accountContext(d.garage, d.races, null, d.events))
                val a = advisor.ask(AiRequest(CoachPrompts.system(), prompt, webSearch = true))
                store.setBriefing(a.text, a.sources.map { "${it.title}|${it.url}" })
            } catch (e: Exception) {
                _briefingError.value = e.message
            } finally {
                _briefingBusy.value = false
            }
        }
    }

    /** Lê prints da galeria com OCR e preenche a garagem / histórico automaticamente. */
    fun scanScreenshots(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val ocr = OcrEngine()
            var parts = 0; var drivers = 0; var results = 0; var unknown = 0
            try {
                for (uri in uris) {
                    val bmp = withContext(Dispatchers.IO) { ImageUtils.loadUri(app, uri) } ?: continue
                    val p = ScreenReader.parse(ocr.read(bmp))
                    val name = p.itemName
                    when {
                        p.carStats != null && p.slot != null && name != null -> { store.mergeScannedPart(name, p.slot!!, p.carStats!!, p.level, p.cards); parts++ }
                        p.driverStats != null && name != null -> { store.mergeScannedDriver(name, p.driverStats!!, p.level, p.cards); drivers++ }
                        p.screen == GameScreen.RACE_RESULT && p.won != null -> {
                            store.addRace(RaceRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), p.track?.id ?: "desconhecida",
                                finishPositions = p.positions.take(2), won = p.won, source = "print"))
                            results++
                        }
                        else -> unknown++
                    }
                }
            } finally {
                ocr.close()
            }
            toast("Lidos: $parts peças, $drivers pilotos, $results resultados" + if (unknown > 0) " • $unknown prints não reconhecidos (use o Coach IA para eles)" else "")
        }
    }

    fun loadNews() {
        if (_newsLoading.value) return
        _newsLoading.value = true
        viewModelScope.launch {
            val (items, errors) = NewsRepository.fetch()
            _news.value = items
            _newsErrors.value = errors
            _newsLoading.value = false
        }
    }

    // ---------- Checklist diário ----------
    fun toggleDaily(item: String) {
        val today = LocalDate.now().toString()
        store.update { d ->
            val cur = if (d.daily.day == today) d.daily.done else emptySet()
            d.copy(daily = d.daily.copy(day = today, done = if (item in cur) cur - item else cur + item))
        }
    }

    companion object {
        val DAILY_ITEMS = listOf(
            "Abrir caixas grátis / recompensas diárias",
            "Ver anúncios de recompensa (moedas e cartas)",
            "Conferir a loja diária (cartas das peças prioritárias)",
            "Correr as corridas do dia na liga/série",
            "Jogar o evento ativo (Grand Prix / evento especial)",
            "Pedir e doar cartas no clube",
            "Usar boosters só em corridas que valem mais",
            "Atualizar o meta da semana no Coach IA",
        )
    }
}
