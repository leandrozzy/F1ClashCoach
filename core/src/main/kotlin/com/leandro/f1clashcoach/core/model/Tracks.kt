package com.leandro.f1clashcoach.core.model

import kotlinx.serialization.Serializable

/** Peso de cada atributo do carro para o desempenho em uma pista. */
@Serializable
data class StatWeights(
    val speed: Double = 1.0,
    val cornering: Double = 1.0,
    val powerUnit: Double = 1.0,
    val qualifying: Double = 1.0,
    /** Peso aplicado a cada 0,1 s de pit stop economizado. */
    val pitStop: Double = 1.0,
    val overtakeMode: Double = 1.0,
) {
    fun score(s: CarStats): Double =
        speed * s.speed + cornering * s.cornering + powerUnit * s.powerUnit +
            qualifying * s.qualifying + overtakeMode * s.overtakeMode - pitStop * s.pitStop * 10.0
}

@Serializable
data class DriverWeights(
    val overtaking: Double = 1.0,
    val defending: Double = 1.0,
    val qualifying: Double = 1.0,
    val raceStart: Double = 1.0,
    val tyreManagement: Double = 1.0,
) {
    fun score(s: DriverStats): Double =
        overtaking * s.overtaking + defending * s.defending + qualifying * s.qualifying +
            raceStart * s.raceStart + tyreManagement * s.tyreManagement
}

@Serializable
enum class TrackType(val label: String, val car: StatWeights, val driver: DriverWeights) {
    POWER(
        "Velocidade / reta longa",
        StatWeights(speed = 1.5, cornering = 0.7, powerUnit = 1.3, qualifying = 0.9, pitStop = 0.9, overtakeMode = 1.2),
        DriverWeights(overtaking = 1.3, defending = 1.1, qualifying = 0.8, raceStart = 0.9, tyreManagement = 0.9),
    ),
    BALANCED(
        "Equilibrada",
        StatWeights(speed = 1.1, cornering = 1.1, powerUnit = 1.0, qualifying = 1.0, pitStop = 1.0, overtakeMode = 1.0),
        DriverWeights(),
    ),
    TECHNICAL(
        "Técnica / muitas curvas",
        StatWeights(speed = 0.7, cornering = 1.6, powerUnit = 0.9, qualifying = 1.2, pitStop = 1.0, overtakeMode = 0.8),
        DriverWeights(overtaking = 0.9, defending = 1.2, qualifying = 1.2, raceStart = 1.0, tyreManagement = 1.1),
    ),
    STREET(
        "Rua / difícil ultrapassar",
        StatWeights(speed = 0.7, cornering = 1.4, powerUnit = 0.8, qualifying = 1.7, pitStop = 1.2, overtakeMode = 0.7),
        DriverWeights(overtaking = 0.8, defending = 1.4, qualifying = 1.6, raceStart = 1.3, tyreManagement = 0.9),
    ),
}

@Serializable
data class Track(
    val id: String,
    val name: String,
    val country: String,
    val type: TrackType,
    /** 0..1 — quanto é difícil ultrapassar. */
    val overtakeDifficulty: Double,
    /** 0..1 — chance relativa de chuva no jogo (estimativa). */
    val rainRisk: Double,
    /** 0..1 — agressividade da pista com os pneus. */
    val tyreStress: Double,
    /** Nomes que podem aparecer na tela (OCR). */
    val aliases: List<String> = emptyList(),
) {
    val carWeights: StatWeights get() = type.car
    val driverWeights: DriverWeights get() = type.driver
}

/**
 * Catálogo das pistas do calendário. Os perfis são estimativas iniciais —
 * o app recalibra com seus resultados e com o meta buscado na web pela IA.
 */
object TrackCatalog {
    val tracks: List<Track> = listOf(
        Track("bahrain", "Bahrein", "Sakhir", TrackType.BALANCED, 0.3, 0.05, 0.8, listOf("BAHRAIN", "SAKHIR")),
        Track("jeddah", "Arábia Saudita", "Jeddah", TrackType.POWER, 0.4, 0.05, 0.4, listOf("SAUDI", "JEDDAH")),
        Track("melbourne", "Austrália", "Melbourne", TrackType.BALANCED, 0.5, 0.3, 0.5, listOf("AUSTRALIA", "MELBOURNE", "ALBERT PARK")),
        Track("suzuka", "Japão", "Suzuka", TrackType.TECHNICAL, 0.6, 0.45, 0.8, listOf("JAPAN", "SUZUKA")),
        Track("shanghai", "China", "Xangai", TrackType.BALANCED, 0.4, 0.35, 0.7, listOf("CHINA", "SHANGHAI")),
        Track("miami", "Miami", "EUA", TrackType.POWER, 0.45, 0.3, 0.5, listOf("MIAMI")),
        Track("imola", "Emília-Romanha", "Imola", TrackType.TECHNICAL, 0.75, 0.35, 0.5, listOf("IMOLA", "EMILIA")),
        Track("monaco", "Mônaco", "Monte Carlo", TrackType.STREET, 0.95, 0.25, 0.2, listOf("MONACO", "MÔNACO", "MONTE CARLO")),
        Track("montreal", "Canadá", "Montreal", TrackType.POWER, 0.35, 0.4, 0.4, listOf("CANADA", "MONTREAL", "GILLES")),
        Track("barcelona", "Espanha", "Barcelona", TrackType.TECHNICAL, 0.6, 0.15, 0.8, listOf("SPAIN", "BARCELONA", "CATALUNYA")),
        Track("madrid", "Madri", "Espanha", TrackType.BALANCED, 0.5, 0.15, 0.5, listOf("MADRID", "MADRING")),
        Track("austria", "Áustria", "Spielberg", TrackType.POWER, 0.35, 0.45, 0.5, listOf("AUSTRIA", "SPIELBERG", "RED BULL RING")),
        Track("silverstone", "Grã-Bretanha", "Silverstone", TrackType.BALANCED, 0.45, 0.6, 0.85, listOf("BRITAIN", "BRITISH", "SILVERSTONE")),
        Track("spa", "Bélgica", "Spa", TrackType.POWER, 0.3, 0.7, 0.6, listOf("BELGIUM", "SPA")),
        Track("hungaroring", "Hungria", "Budapeste", TrackType.TECHNICAL, 0.85, 0.3, 0.6, listOf("HUNGARY", "HUNGARORING", "BUDAPEST")),
        Track("zandvoort", "Holanda", "Zandvoort", TrackType.TECHNICAL, 0.85, 0.45, 0.6, listOf("NETHERLANDS", "DUTCH", "ZANDVOORT")),
        Track("monza", "Itália", "Monza", TrackType.POWER, 0.25, 0.3, 0.4, listOf("ITALY", "ITALIAN", "MONZA")),
        Track("baku", "Azerbaijão", "Baku", TrackType.POWER, 0.4, 0.1, 0.4, listOf("AZERBAIJAN", "BAKU")),
        Track("singapore", "Singapura", "Marina Bay", TrackType.STREET, 0.9, 0.4, 0.5, listOf("SINGAPORE", "MARINA BAY")),
        Track("austin", "EUA", "Austin (COTA)", TrackType.BALANCED, 0.4, 0.2, 0.7, listOf("USA", "UNITED STATES", "AUSTIN", "COTA", "AMERICAS")),
        Track("mexico", "México", "Cidade do México", TrackType.POWER, 0.5, 0.15, 0.4, listOf("MEXICO", "MÉXICO")),
        Track("interlagos", "Brasil", "Interlagos", TrackType.BALANCED, 0.35, 0.65, 0.6, listOf("BRAZIL", "BRASIL", "SAO PAULO", "SÃO PAULO", "INTERLAGOS")),
        Track("lasvegas", "Las Vegas", "EUA", TrackType.POWER, 0.3, 0.05, 0.3, listOf("LAS VEGAS", "VEGAS")),
        Track("qatar", "Catar", "Lusail", TrackType.BALANCED, 0.5, 0.05, 0.9, listOf("QATAR", "LUSAIL")),
        Track("abudhabi", "Abu Dhabi", "Yas Marina", TrackType.BALANCED, 0.55, 0.05, 0.5, listOf("ABU DHABI", "YAS MARINA")),
    )

    fun byId(id: String?): Track? = tracks.firstOrNull { it.id == id }

    /** Encontra a pista mencionada num texto de OCR. */
    fun match(text: String): Track? {
        val up = text.uppercase()
        return tracks
            .flatMap { t -> t.aliases.map { a -> t to a } }
            .filter { (_, alias) -> Regex("\\b" + Regex.escape(alias) + "\\b").containsMatchIn(up) }
            .maxByOrNull { (_, alias) -> alias.length }
            ?.first
    }
}
