package com.leandro.f1clashcoach.core

import com.leandro.f1clashcoach.core.engine.DailyBriefing
import com.leandro.f1clashcoach.core.engine.GameSync
import com.leandro.f1clashcoach.core.engine.UpgradePlanner
import com.leandro.f1clashcoach.core.model.Garage
import com.leandro.f1clashcoach.core.model.GrandPrixState
import com.leandro.f1clashcoach.core.model.Objective
import com.leandro.f1clashcoach.core.model.PartSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Navegar pelas telas (como no vídeo) deve montar a conta inteira sozinho. */
class VideoSyncTest {
    private val now = 1_800_000_000_000L
    private val order = listOf(
        "home", "store_featured", "store_crates", "objectives", "objectives_weekly", "team_drivers_top", "team_drivers_grid",
        "team_drivers_table", "team_cars_top", "team_cars_grid", "team_cars_grid2", "boosters", "club_members", "club_league",
        "pit_pass", "race_menu", "gp_detail", "gp_board",
    )

    fun run(): Triple<Garage, List<Objective>, GrandPrixState?> {
        var g = Garage(playerName = "Leandro Ribeiro")
        var objectives = emptyList<Objective>()
        var gp: GrandPrixState? = null
        var lastSlot: PartSlot? = null
        for (name in order) {
            val parsed = RealFramesTest.p(name, lastSlot)
            val read = parsed.game ?: continue
            read.tiles.lastOrNull { it.slot != null }?.slot?.let { lastSlot = it }
            val r = GameSync.apply(g, read, objectives, gp, now)
            g = r.garage
            r.objectives?.let { objectives = it }
            r.gp?.let { gp = it }
        }
        return Triple(g, objectives, gp)
    }

    @Test fun buildsAccountFromScreens() {
        val (g, objectives, gp) = run()
        // Última tela é depois de pagar a entrada do GP (400 mil).
        assertEquals(46_272_703L, g.coins)
        assertEquals(1071, g.teamScore)
        assertEquals(8, g.series)
        assertEquals(7, g.parts.count { it.equipped })
        assertTrue("peças: ${g.parts.map { it.name }}", g.parts.size >= 15)
        assertTrue(g.parts.all { it.slot in PartSlot.entries })
        val eqDrivers = g.drivers.filter { it.equipped }
        assertEquals(listOf("D. Hill", "L.Lzwson"), eqDrivers.map { it.name })
        assertTrue(eqDrivers.first().legendary)
        assertEquals(65, eqDrivers.first().stats.defending)
        assertTrue("pilotos: ${g.drivers.size}", g.drivers.size >= 15)
        assertTrue(objectives.size >= 8)
        assertEquals("austin", gp?.trackId)
        assertEquals(0, gp?.racesPlayed)
        assertEquals(0, gp?.myPoints)
        assertTrue(g.featuredAssets.isNotEmpty())
        assertEquals(19, g.boosters["Cogumelo"])
    }

    @Test fun briefingFromVideo() {
        val (g, objectives, gp) = run()
        val items = DailyBriefing.build(g, emptyList(), emptyList(), emptyList(), 0, now, objectives, gp)
        println("==== PLANO DO DIA (gerado do vídeo) ====")
        items.forEach { println("[${it.priority}] ${it.icon} ${it.title}\n      ${it.detail}") }
        println("==== UPGRADES ====")
        UpgradePlanner.plan(g).ranked.filter { it.canUpgradeNow }.take(10).forEach { println("${it.name} (${it.kind}) ${it.reason}") }
        assertTrue(items.any { it.title.contains("GP EUA") })
        assertTrue(items.any { it.title.contains("itens com cartas para evoluir") })
        assertTrue(items.any { it.title.startsWith("Diário") })
        assertTrue(items.none { it.title.contains("Complete a leitura da garagem") })
        assertTrue(items.none { it.detail.contains("Hadjar") })
        assertTrue(items.none { it.title.contains("Entre no jogo. Abrz") })
    }
}
