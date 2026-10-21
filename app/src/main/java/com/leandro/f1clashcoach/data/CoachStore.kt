package com.leandro.f1clashcoach.data

import android.content.Context
import com.leandro.f1clashcoach.core.ai.ChatTurn
import com.leandro.f1clashcoach.core.model.ActiveEvent
import com.leandro.f1clashcoach.core.model.CarStats
import com.leandro.f1clashcoach.core.model.GameMode
import com.leandro.f1clashcoach.core.model.GrandPrixState
import com.leandro.f1clashcoach.core.model.Objective
import com.leandro.f1clashcoach.core.engine.GameRead
import com.leandro.f1clashcoach.core.engine.GameSync
import com.leandro.f1clashcoach.core.model.Driver
import com.leandro.f1clashcoach.core.model.DriverStats
import com.leandro.f1clashcoach.core.model.Garage
import com.leandro.f1clashcoach.core.model.Part
import com.leandro.f1clashcoach.core.model.PartSlot
import com.leandro.f1clashcoach.core.model.Progress
import com.leandro.f1clashcoach.core.model.RaceRecord
import com.leandro.f1clashcoach.core.model.TyreObservation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class ChatMessage(val fromUser: Boolean, val text: String, val sources: List<String> = emptyList(), val time: Long = System.currentTimeMillis()) {
    fun toTurn() = ChatTurn(fromUser, text)
}

@Serializable
data class DailyCheck(val day: String = "", val done: Set<String> = emptySet())

@Serializable
data class CoachData(
    val garage: Garage = Garage(),
    val races: List<RaceRecord> = emptyList(),
    val tyreObservations: List<TyreObservation> = emptyList(),
    val chat: List<ChatMessage> = emptyList(),
    val daily: DailyCheck = DailyCheck(),
    val lastMeta: String = "",
    val lastMetaTime: Long = 0,
    val events: List<ActiveEvent> = emptyList(),
    /** Briefing diário gerado automaticamente pela IA. */
    val aiBriefing: String = "",
    val aiBriefingSources: List<String> = emptyList(),
    val aiBriefingTime: Long = 0,
    /** Objetivos diários/semanais lidos da tela do jogo. */
    val objectives: List<Objective> = emptyList(),
    /** Grand Prix em andamento (lido das telas do GP). */
    val gp: GrandPrixState? = null,
    /** Séries de Duelos lidas da tela (faixa de pontuação, bandeiras, prêmios). */
    val series: Map<Int, com.leandro.f1clashcoach.core.model.SeriesInfo> = emptyMap(),
    /** Total de bandeiras da conta. */
    val flags: Int? = null,
    /** Meta para chegar à série alvo (pesquisa da IA). */
    val targetMeta: String = "",
    val targetMetaTime: Long = 0,
    /** Clube: membros acompanhados, regras e liga. */
    val club: com.leandro.f1clashcoach.core.engine.ClubState = com.leandro.f1clashcoach.core.engine.ClubState(),
    /** Regra digitada pelo presidente (não sobrescrever com a lida da tela). */
    val clubRulesManual: Boolean = false,
    /** Quando o próximo caixote grátis fica pronto. */
    val freeCrateAt: Long? = null,
)

/** Tudo que o Coach sabe fica neste arquivo JSON, no próprio aparelho. */
class CoachStore(context: Context) {
    private val file = File(context.filesDir, "coach-data.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    private val _data = MutableStateFlow(load())
    val data: StateFlow<CoachData> = _data

    private fun load(): CoachData = runCatching {
        if (file.exists()) json.decodeFromString(CoachData.serializer(), file.readText()) else CoachData()
    }.getOrElse { CoachData() }

    @Synchronized
    fun update(transform: (CoachData) -> CoachData) {
        val next = transform(_data.value)
        if (next == _data.value) return
        _data.value = next
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(CoachData.serializer(), next))
        tmp.renameTo(file)
    }

    fun exportJson(): String = json.encodeToString(CoachData.serializer(), _data.value)

    fun importJson(text: String) {
        val parsed = json.decodeFromString(CoachData.serializer(), text)
        update { parsed }
    }

    // ---------- Garagem ----------
    fun upsertPart(p: Part) = update { d ->
        val list = d.garage.parts.filterNot { it.id == p.id }.let { rest ->
            if (p.equipped) rest.map { if (it.slot == p.slot) it.copy(equipped = false) else it } else rest
        }
        d.copy(garage = d.garage.copy(parts = list + p))
    }

    fun removePart(id: String) = update { d -> d.copy(garage = d.garage.copy(parts = d.garage.parts.filterNot { it.id == id })) }

    fun upsertDriver(dr: Driver) = update { d ->
        var list = d.garage.drivers.filterNot { it.id == dr.id }
        if (dr.equipped && list.count { it.equipped } >= 2) {
            // Só dois pilotos escalados: tira o mais fraco.
            val weakest = list.filter { it.equipped }.minByOrNull { it.stats.total }
            list = list.map { if (it.id == weakest?.id) it.copy(equipped = false) else it }
        }
        d.copy(garage = d.garage.copy(drivers = list + dr))
    }

    fun removeDriver(id: String) = update { d -> d.copy(garage = d.garage.copy(drivers = d.garage.drivers.filterNot { it.id == id })) }

    /** Atualização vinda do OCR: casa pelo nome (e slot) e preserva o que não foi lido. */
    fun mergeScannedPart(name: String, slot: PartSlot, stats: CarStats, level: Int?, cards: Pair<Int, Int>?): Boolean {
        val g = _data.value.garage
        val existing = g.parts.firstOrNull { it.slot == slot && it.name.equals(name, ignoreCase = true) }
        val progress = (existing?.progress ?: Progress()).let { p ->
            p.copy(level = level ?: p.level, cardsOwned = cards?.first ?: p.cardsOwned, cardsNeeded = cards?.second ?: p.cardsNeeded)
        }
        val part = existing?.copy(stats = stats, progress = progress)
            ?: Part(UUID.randomUUID().toString(), slot, name, stats = stats, progress = progress)
        if (existing == part) return false
        upsertPart(part)
        return true
    }

    fun mergeScannedDriver(name: String, stats: DriverStats, level: Int?, cards: Pair<Int, Int>?): Boolean {
        val g = _data.value.garage
        val existing = g.drivers.firstOrNull { it.name.equals(name, ignoreCase = true) }
        val progress = (existing?.progress ?: Progress()).let { p ->
            p.copy(level = level ?: p.level, cardsOwned = cards?.first ?: p.cardsOwned, cardsNeeded = cards?.second ?: p.cardsNeeded)
        }
        val driver = existing?.copy(stats = stats, progress = progress)
            ?: Driver(UUID.randomUUID().toString(), name, stats = stats, progress = progress)
        if (existing == driver) return false
        upsertDriver(driver)
        return true
    }

    // ---------- Corridas ----------
    fun addRace(r: RaceRecord) = update { d -> d.copy(races = (d.races + r).takeLast(2000)) }
    fun removeRace(id: String) = update { d -> d.copy(races = d.races.filterNot { it.id == id }) }
    fun addTyreObservation(o: TyreObservation) = update { d -> d.copy(tyreObservations = (d.tyreObservations + o).takeLast(3000)) }

    // ---------- Sincronização com as telas do jogo ----------
    /** Aplica uma leitura de tela à conta. Retorna o que mudou (para avisar no painel). */
    fun applyGameRead(read: GameRead, now: Long = System.currentTimeMillis()): List<String> {
        var changes = emptyList<String>()
        update { d ->
            val r = GameSync.apply(d.garage, read, d.objectives, d.gp, now)
            changes = r.changes
            var club = d.club
            if (read.clubMembers.isNotEmpty()) club = com.leandro.f1clashcoach.core.engine.ClubManager.merge(club, read.clubMembers, now)
            read.clubMemberCount?.let { club = club.copy(memberCount = it) }
            read.clubRep?.let { club = club.copy(rep = it) }
            read.clubLeagueName?.let { club = club.copy(leagueName = it) }
            read.clubLeagueLeftMs?.let { club = club.copy(leagueEndsAt = now + it) }
            if (read.clubRules != null && !d.clubRulesManual && read.clubRules != club.rules.text)
                club = club.copy(rules = com.leandro.f1clashcoach.core.engine.ClubManager.parseRules(read.clubRules!!))
            d.copy(
                garage = r.garage, objectives = r.objectives ?: d.objectives, gp = r.gp ?: d.gp,
                series = d.series + r.series.associateBy { it.number }, flags = r.flags ?: d.flags,
                club = club, freeCrateAt = read.freeCrateLeftMs?.let { now + it } ?: if (read.freeCrateReady) now else d.freeCrateAt,
            )
        }
        return changes
    }

    // ---------- Eventos / modos ----------
    /** Registra um evento visto na tela. Retorna o evento e se é novo. */
    fun upsertEvent(mode: GameMode, name: String, timeLeftMs: Long?, now: Long = System.currentTimeMillis()): Pair<ActiveEvent, Boolean> {
        val key = (mode.name + ":" + name.trim().lowercase()).replace(Regex("\\s+"), " ")
        val cur = _data.value.events.firstOrNull { it.id == key }
        val ev = (cur ?: ActiveEvent(key, mode, name.trim(), firstSeen = now)).copy(
            lastSeen = now,
            endsAt = timeLeftMs?.let { now + it } ?: cur?.endsAt,
        )
        update { d ->
            val others = d.events.filterNot { it.id == key || it.isOver(now) }
            d.copy(events = (others + ev).takeLast(30))
        }
        return ev to (cur == null)
    }

    fun setEventAdvice(id: String, advice: String) = update { d ->
        d.copy(events = d.events.map { if (it.id == id) it.copy(aiAdvice = advice, aiAdviceAt = System.currentTimeMillis()) else it })
    }

    fun removeEvent(id: String) = update { d -> d.copy(events = d.events.filterNot { it.id == id }) }

    fun setBriefing(text: String, sources: List<String>) = update { it.copy(aiBriefing = text, aiBriefingSources = sources, aiBriefingTime = System.currentTimeMillis()) }

    // ---------- Clube ----------
    fun setClubRules(text: String, minRep: Int?, days: Int?) = update { d ->
        val parsed = com.leandro.f1clashcoach.core.engine.ClubManager.parseRules(text)
        d.copy(club = d.club.copy(rules = parsed.copy(minRepWeek = minRep ?: parsed.minRepWeek, maxInactiveDays = days ?: parsed.maxInactiveDays)), clubRulesManual = true)
    }

    fun setRaceReview(id: String, review: String) = update { d -> d.copy(races = d.races.map { if (it.id == id) it.copy(review = review) else it }) }

    // ---------- Chat ----------
    fun addChat(m: ChatMessage) = update { d -> d.copy(chat = (d.chat + m).takeLast(200)) }
    fun clearChat() = update { it.copy(chat = emptyList()) }
}
