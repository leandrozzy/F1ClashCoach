package com.leandro.f1clashcoach.core.model

import kotlinx.serialization.Serializable

/** Peças do carro (temporada 2026: 7 slots, incluindo bateria). */
@Serializable
enum class PartSlot(val label: String, val keywords: List<String>) {
    BRAKES("Freios", listOf("BRAKES", "FREIOS")),
    GEARBOX("Câmbio", listOf("GEARBOX", "CÂMBIO", "CAMBIO", "CAIXA")),
    REAR_WING("Asa traseira", listOf("REAR WING", "ASA TRASEIRA")),
    FRONT_WING("Asa dianteira", listOf("FRONT WING", "ASA DIANTEIRA")),
    SUSPENSION("Suspensão", listOf("SUSPENSION", "SUSPENSÃO", "SUSPENSAO")),
    ENGINE("Motor", listOf("ENGINE", "MOTOR")),
    BATTERY("Bateria", listOf("BATTERY", "BATERIA")),
}

@Serializable
enum class Rarity(val label: String) {
    COMMON("Comum"), RARE("Rara"), EPIC("Épica"), LEGENDARY("Lendária"), SPECIAL("Especial"), UNKNOWN("?")
}

/** Atributos de uma peça. [pitStop] é o tempo médio de pit stop em segundos (menor é melhor). */
@Serializable
data class CarStats(
    val speed: Int = 0,
    val cornering: Int = 0,
    val powerUnit: Int = 0,
    val qualifying: Int = 0,
    val pitStop: Double = 0.0,
    val overtakeMode: Int = 0,
) {
    operator fun plus(o: CarStats) = CarStats(
        speed + o.speed, cornering + o.cornering, powerUnit + o.powerUnit,
        qualifying + o.qualifying, pitStop + o.pitStop, overtakeMode + o.overtakeMode,
    )

    operator fun minus(o: CarStats) = CarStats(
        speed - o.speed, cornering - o.cornering, powerUnit - o.powerUnit,
        qualifying - o.qualifying, pitStop - o.pitStop, overtakeMode - o.overtakeMode,
    )

    val total: Int get() = speed + cornering + powerUnit + qualifying + overtakeMode
}

@Serializable
data class DriverStats(
    val overtaking: Int = 0,
    val defending: Int = 0,
    val qualifying: Int = 0,
    val raceStart: Int = 0,
    val tyreManagement: Int = 0,
) {
    operator fun minus(o: DriverStats) = DriverStats(
        overtaking - o.overtaking, defending - o.defending, qualifying - o.qualifying,
        raceStart - o.raceStart, tyreManagement - o.tyreManagement,
    )

    val total: Int get() = overtaking + defending + qualifying + raceStart + tyreManagement
}

/** Dados de evolução de um item (peça ou piloto). */
@Serializable
data class Progress(
    val level: Int = 1,
    val cardsOwned: Int = 0,
    val cardsNeeded: Int = 0,
    /** Custo em moedas do próximo nível (0 = desconhecido). */
    val upgradeCoins: Int = 0,
) {
    val canUpgrade: Boolean get() = cardsNeeded > 0 && cardsOwned >= cardsNeeded
    val cardsMissing: Int get() = (cardsNeeded - cardsOwned).coerceAtLeast(0)
}

@Serializable
data class Part(
    val id: String,
    val slot: PartSlot,
    val name: String,
    val rarity: Rarity = Rarity.UNKNOWN,
    val stats: CarStats = CarStats(),
    val progress: Progress = Progress(),
    /** Atributos no próximo nível, se o usuário (ou OCR) souber. */
    val nextLevelStats: CarStats? = null,
    val equipped: Boolean = false,
    /** Recurso em destaque da semana (+10% nas estatísticas em eventos). */
    val featured: Boolean = false,
    /** Nível mínimo de GP em que pode ser usada ("Júnior", "Competidor"…). */
    val minTier: String? = null,
)

@Serializable
data class Driver(
    val id: String,
    val name: String,
    val rarity: Rarity = Rarity.UNKNOWN,
    val stats: DriverStats = DriverStats(),
    val progress: Progress = Progress(),
    val nextLevelStats: DriverStats? = null,
    val equipped: Boolean = false,
    val featured: Boolean = false,
    val minTier: String? = null,
) {
    /** Lendário = não está no grid atual (alguns GPs proíbem lendários). */
    val legendary: Boolean get() = !CurrentGrid.isCurrent(name)
}

/** Pilotos do grid atual da F1 (o resto do jogo são lendas/edições especiais). */
object CurrentGrid {
    private val SURNAMES = setOf(
        "VERSTAPPEN", "HADJAR", "LECLERC", "HAMILTON", "RUSSELL", "ANTONELLI", "NORRIS", "PIASTRI", "ALONSO", "STROLL",
        "GASLY", "COLAPINTO", "ALBON", "SAINZ", "OCON", "BEARMAN", "HULKENBERG", "BORTOLETO", "LAWSON", "LINDBLAD",
        "PEREZ", "BOTTAS", "TSUNODA", "DOOHAN",
    )

    fun isCurrent(name: String): Boolean {
        val up = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").uppercase()
        return SURNAMES.any { up.contains(it) }
    }
}

@Serializable
data class Garage(
    val parts: List<Part> = emptyList(),
    val drivers: List<Driver> = emptyList(),
    val coins: Long = 0,
    val bucks: Long = 0,
    val series: Int = 1,
    val trophies: Int = 0,
    val playerName: String = "",
    /** Pontuação da equipe mostrada no jogo. */
    val teamScore: Int = 0,
    /** Totais do carro equipado (aba Equipe → Carros). */
    val carTotals: CarStats? = null,
    /** Recursos em destaque da semana vistos na loja/eventos. */
    val featuredAssets: List<String> = emptyList(),
    val featuredSeenAt: Long = 0,
    /** Reforços (boosters) disponíveis e quantidade. */
    val boosters: Map<String, Int> = emptyMap(),
    val clubName: String = "",
    val updatedAt: Long = 0,
)

@Serializable
data class Objective(
    val weekly: Boolean,
    val text: String,
    val progress: Int,
    val target: Int,
    val reward: Long? = null,
    val endsAt: Long? = null,
    val seenAt: Long = 0,
) {
    val done: Boolean get() = progress >= target
}

@Serializable
data class GrandPrixState(
    val name: String,
    val trackId: String? = null,
    val round: String? = null,
    val roundEndsAt: Long? = null,
    val recommendedScore: Int? = null,
    val entryFee: Long? = null,
    val legendaryAllowed: Boolean? = null,
    val tier: String? = null,
    val racesPlayed: Int? = null,
    val racesTotal: Int? = null,
    val featuredBoostPct: Int? = null,
    val myPoints: Int? = null,
    val seenAt: Long = 0,
)

@Serializable
enum class Tyre(val label: String, val short: String) {
    SOFT("Macio", "S"), MEDIUM("Médio", "M"), HARD("Duro", "H"), INTER("Intermediário", "I"), WET("Chuva", "W");

    val isDry: Boolean get() = this == SOFT || this == MEDIUM || this == HARD
}

@Serializable
enum class Weather(val label: String) { DRY("Seco"), LIGHT_RAIN("Chuva leve"), HEAVY_RAIN("Chuva forte") }

/** Ritmo do piloto. */
@Serializable
enum class DrivingPace(val label: String) { CONSERVE("Conservar"), NEUTRAL("Neutro"), ATTACK("Atacar") }

/** Modo de energia (bateria / ERS). */
@Serializable
enum class EnergyMode(val label: String) { HARVEST("Recarregar"), NEUTRAL("Neutro"), DEPLOY("Usar energia"), OVERTAKE("Overtake Mode") }

@Serializable
data class RaceRecord(
    val id: String,
    val timestamp: Long,
    val trackId: String,
    val series: Int = 0,
    val gridPositions: List<Int> = emptyList(),
    val finishPositions: List<Int> = emptyList(),
    val won: Boolean? = null,
    val weather: Weather = Weather.DRY,
    val strategy: String = "",
    val trophiesDelta: Int = 0,
    val notes: String = "",
    val source: String = "manual",
    /** Pontuação da sua equipe e do rival no pareamento (para calibrar a chance de vitória). */
    val myScore: Int? = null,
    val rivalScore: Int? = null,
    /** Análise pós-corrida feita pela IA. */
    val review: String = "",
)

/** Observação real de desgaste de pneu (alimenta a calibração automática). */
@Serializable
data class TyreObservation(
    val trackId: String,
    val tyre: Tyre,
    val lapsOnTyre: Double,
    val wearPercent: Double,
    val pace: DrivingPace = DrivingPace.NEUTRAL,
    val timestamp: Long = 0,
)
