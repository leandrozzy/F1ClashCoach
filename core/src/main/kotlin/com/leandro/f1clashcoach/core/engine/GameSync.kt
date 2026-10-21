package com.leandro.f1clashcoach.core.engine

import com.leandro.f1clashcoach.core.model.Driver
import com.leandro.f1clashcoach.core.model.Garage
import com.leandro.f1clashcoach.core.model.GrandPrixState
import com.leandro.f1clashcoach.core.model.Objective
import com.leandro.f1clashcoach.core.model.Part
import com.leandro.f1clashcoach.core.model.Progress
import java.util.UUID

/** Resultado de aplicar uma leitura de tela aos dados salvos. */
data class SyncResult(
    val garage: Garage,
    val objectives: List<Objective>? = null,
    val gp: GrandPrixState? = null,
    val changes: List<String> = emptyList(),
    val series: List<com.leandro.f1clashcoach.core.model.SeriesInfo> = emptyList(),
    val flags: Int? = null,
)

/**
 * Transforma o que foi lido na tela em dados da conta — sem o usuário digitar nada.
 * Nunca apaga o que não apareceu na tela; só atualiza o que foi lido.
 */
object GameSync {

    private fun key(name: String) = Txt.compact(name)

    fun apply(
        garage: Garage,
        read: GameRead,
        objectivesNow: List<Objective>,
        gpNow: GrandPrixState?,
        now: Long,
        myName: String = garage.playerName,
    ): SyncResult {
        var g = garage
        val changes = mutableListOf<String>()

        // Loadout limitado de GP mostra outra configuração: só aproveita cartas/níveis, não o equipado.
        val r0 = read
        @Suppress("NAME_SHADOWING")
        val read = if (r0.restricted) r0.copy(teamScore = null, carTotals = null, equippedParts = emptyList(), driverPair = emptyList()) else r0
        read.coins?.let { if (it != g.coins) g = g.copy(coins = it) }
        read.bucks?.let { if (it != g.bucks) g = g.copy(bucks = it) }
        read.series?.let { if (it != g.series) { g = g.copy(series = it); changes += "Série $it" } }
        read.teamScore?.let { if (it != g.teamScore) { g = g.copy(teamScore = it); changes += "Pontuação da equipe $it" } }
        read.carTotals?.let { if (it != g.carTotals) { g = g.copy(carTotals = it); changes += "Atributos do carro" } }
        if (read.boosters.isNotEmpty()) g = g.copy(boosters = g.boosters + read.boosters)
        read.clubName?.let { g = g.copy(clubName = it) }
        if (read.featured.isNotEmpty()) {
            val fresh = now - g.featuredSeenAt > 3L * 86_400_000
            val list = (if (fresh) read.featured else (g.featuredAssets + read.featured)).distinct()
            g = g.copy(featuredAssets = list, featuredSeenAt = now)
        }

        // ---- Peças ----
        val parts = g.parts.toMutableList()
        fun upsertPart(t: GridTile, equipped: Boolean?) {
            val idx = parts.indexOfFirst { key(it.name) == key(t.name) && (t.slot == null || it.slot == t.slot) }
            if (idx < 0) {
                val slot = t.slot ?: return
                parts += Part(
                    UUID.randomUUID().toString(), slot, t.name,
                    progress = Progress(t.level ?: 1, t.cardsOwned, t.cardsNeeded), equipped = equipped == true, featured = t.featured,
                )
                changes += "Nova peça: ${t.name}"
            } else {
                val p = parts[idx]
                val np = p.copy(
                    progress = p.progress.copy(level = t.level ?: p.progress.level, cardsOwned = t.cardsOwned, cardsNeeded = t.cardsNeeded),
                    featured = t.featured || p.featured,
                    equipped = equipped ?: p.equipped,
                )
                if (np != p) parts[idx] = np
            }
        }
        if (read.screen == GameScreen.TEAM_CARS) {
            read.tiles.filter { it.slot != null || parts.any { p -> key(p.name) == key(it.name) } }.forEach { upsertPart(it, null) }
        }
        if (read.equippedParts.size == 7) {
            val eqKeys = read.equippedParts.map { key(it.name) }.toSet()
            read.equippedParts.forEach { upsertPart(it, true) }
            for (i in parts.indices) {
                val p = parts[i]
                if (p.equipped && key(p.name) !in eqKeys) parts[i] = p.copy(equipped = false)
            }
        }

        // ---- Pilotos ----
        val drivers = g.drivers.toMutableList()
        fun findDriver(name: String) = drivers.indexOfFirst { key(it.name) == key(name) }
        val unknown = com.leandro.f1clashcoach.core.model.Rarity.UNKNOWN
        // O mesmo piloto existe em 3 raridades (cartas azul/laranja/roxa): casa por nome + raridade.
        fun findTile(t: GridTile): Int {
            val same = drivers.indices.filter { key(drivers[it].name) == key(t.name) }
            // lendários e especiais têm uma carta só: a cor não separa nada
            if (com.leandro.f1clashcoach.core.model.Catalog.findDrivers(t.name).size == 1) return same.firstOrNull() ?: -1
            if (t.rarity == unknown) return same.firstOrNull { drivers[it].progress.cardsNeeded == t.cardsNeeded } ?: if (same.size == 1) same[0] else -1
            return same.firstOrNull { drivers[it].rarity == t.rarity } ?: same.firstOrNull { drivers[it].rarity == unknown } ?: -1
        }
        for (t in read.tiles.filter { read.screen == GameScreen.TEAM_DRIVERS }) {
            val i = findTile(t)
            if (i < 0) {
                drivers += Driver(UUID.randomUUID().toString(), t.name, rarity = t.rarity, progress = Progress(t.level ?: 1, t.cardsOwned, t.cardsNeeded), featured = t.featured)
                changes += "Novo piloto: ${t.name}" + if (t.rarity != unknown) " (${t.rarity.label})" else ""
            } else {
                val d = drivers[i]
                drivers[i] = d.copy(
                    rarity = if (t.rarity != unknown) t.rarity else d.rarity,
                    progress = d.progress.copy(level = t.level ?: d.progress.level, cardsOwned = t.cardsOwned, cardsNeeded = t.cardsNeeded),
                    featured = t.featured || d.featured,
                )
            }
        }
        if (read.driverPair.size == 2 && read.driverPair.all { it.first != null }) {
            val eq = read.driverPair.map { key(it.first!!) }.toSet()
            for (i in drivers.indices) if (drivers[i].equipped && key(drivers[i].name) !in eq) drivers[i] = drivers[i].copy(equipped = false)
            for (i in drivers.indices) if (drivers[i].equipped) drivers[i] = drivers[i].copy(equipped = false)
            for ((name, stats) in read.driverPair) {
                // Os atributos exatos dizem qual carta (raridade + nível) está escalada.
                val exact = com.leandro.f1clashcoach.core.model.Catalog.matchDriverStats(name!!, stats)
                val rar = exact?.first?.rarity ?: unknown
                val same = drivers.indices.filter { key(drivers[it].name) == key(name) }
                val i = same.firstOrNull { rar != unknown && drivers[it].rarity == rar } ?: same.firstOrNull { drivers[it].rarity == unknown } ?: if (rar == unknown) same.firstOrNull() ?: -1 else -1
                val lvl = exact?.second
                if (i < 0) drivers += Driver(UUID.randomUUID().toString(), name, rarity = rar, stats = stats, equipped = true, progress = Progress(lvl ?: 1))
                else drivers[i] = drivers[i].copy(stats = stats, equipped = true, rarity = if (rar != unknown) rar else drivers[i].rarity,
                    progress = if (lvl != null) drivers[i].progress.copy(level = lvl) else drivers[i].progress)
            }
            changes += "Pilotos escalados: ${read.driverPair.joinToString { it.first!! }}"
        }
        // Tela de detalhe: raridade, nível mínimo de GP, custo e ganho do próximo nível.
        read.asset?.let { a ->
            val sur = key(a.name.split(" ").last())
            if (a.isDriver) {
                val i = drivers.indexOfFirst { key(it.name).endsWith(sur.takeLast(6)) || sur.endsWith(key(it.name).takeLast(6)) }
                if (i >= 0) {
                    val d = drivers[i]
                    drivers[i] = d.copy(
                        rarity = if (a.rarity != com.leandro.f1clashcoach.core.model.Rarity.UNKNOWN) a.rarity else d.rarity,
                        minTier = a.minTier ?: d.minTier,
                        stats = a.driverStats ?: d.stats,
                        nextLevelStats = a.driverStats?.let { st -> a.driverGain?.let { gn ->
                            com.leandro.f1clashcoach.core.model.DriverStats(st.overtaking + gn.overtaking, st.defending + gn.defending, st.qualifying + gn.qualifying, st.raceStart + gn.raceStart, st.tyreManagement + gn.tyreManagement)
                        } } ?: d.nextLevelStats,
                        progress = d.progress.copy(
                            cardsOwned = a.cardsOwned ?: d.progress.cardsOwned, cardsNeeded = a.cardsNeeded ?: d.progress.cardsNeeded,
                            level = a.level ?: d.progress.level, upgradeCoins = a.upgradeCost?.toInt() ?: d.progress.upgradeCoins,
                        ),
                    )
                    changes += "Detalhes de ${d.name} atualizados"
                }
            } else {
                val i = parts.indexOfFirst { key(it.name) == key(a.name) }
                if (i >= 0) {
                    val pt = parts[i]
                    parts[i] = pt.copy(minTier = a.minTier ?: pt.minTier,
                        progress = pt.progress.copy(upgradeCoins = a.upgradeCost?.toInt() ?: pt.progress.upgradeCoins))
                }
            }
        }
        // Marca destaques da semana em quem você tem.
        val feat = g.featuredAssets.map { key(it) }
        fun isFeat(n: String) = feat.any { f -> f.isNotEmpty() && (key(n).endsWith(f.takeLast(6)) || f.endsWith(key(n).takeLast(6))) }
        for (i in drivers.indices) if (!drivers[i].featured && isFeat(drivers[i].name)) drivers[i] = drivers[i].copy(featured = true)
        for (i in parts.indices) if (!parts[i].featured && isFeat(parts[i].name)) parts[i] = parts[i].copy(featured = true)

        // Catálogo da temporada: nível pelas cartas, atributos e custo de cada nível.
        g = com.leandro.f1clashcoach.core.model.Catalog.enrich(g.copy(parts = parts, drivers = drivers, updatedAt = now))

        // ---- Objetivos ----
        var objectives: List<Objective>? = null
        if (read.objectives.isNotEmpty()) {
            val dEnd = read.objectivesDailyLeftMs?.let { now + it }
            val wEnd = read.objectivesWeeklyLeftMs?.let { now + it }
            val fresh = read.objectives.map { o ->
                Objective(o.weekly, o.text, o.progress, o.target, o.reward, if (o.weekly) wEnd else dEnd, now)
            }
            // Mescla com o que já sabíamos (a tela rola: cada leitura mostra só uma parte).
            val merged = LinkedHashMap<String, Objective>()
            objectivesNow.filter { it.endsAt == null || it.endsAt > now }.forEach { merged[key(it.text)] = it }
            fresh.forEach { o ->
                val k = merged.keys.firstOrNull { Txt.lev(it, key(o.text), 4) <= 4 } ?: key(o.text)
                merged[k] = o.copy(endsAt = o.endsAt ?: merged[k]?.endsAt)
            }
            objectives = merged.values.toList()
        }

        // ---- Grand Prix ----
        var gp: GrandPrixState? = null
        read.gp?.let { r ->
            val base = gpNow?.takeIf { it.name == r.name } ?: GrandPrixState(r.name)
            val mine = r.leaderboard.firstOrNull { myName.isNotBlank() && Txt.has(key(it.first), key(myName).take(10)) }?.second
            gp = base.copy(
                trackId = r.trackId ?: base.trackId,
                round = r.round ?: base.round,
                roundEndsAt = r.timeLeftMs?.let { now + it } ?: base.roundEndsAt,
                recommendedScore = r.recommendedScore ?: base.recommendedScore,
                entryFee = r.entryFee ?: base.entryFee,
                legendaryAllowed = r.legendaryAllowed ?: base.legendaryAllowed,
                tier = r.tier ?: base.tier,
                racesPlayed = r.racesPlayed?.let { (it - 1).coerceAtLeast(0) } ?: base.racesPlayed,
                racesTotal = r.racesTotal ?: base.racesTotal,
                featuredBoostPct = r.featuredBoostPct ?: base.featuredBoostPct,
                myPoints = mine ?: base.myPoints,
                seenAt = now,
            )
        }
        return SyncResult(g, objectives, gp, changes, read.seriesCards.map { it.copy(seenAt = now) }, read.flags)
    }
}
