package com.leandro.f1clashcoach.core

import com.leandro.f1clashcoach.core.RealFramesTest.Companion.p
import com.leandro.f1clashcoach.core.engine.GameScreen
import com.leandro.f1clashcoach.core.engine.GameSync
import com.leandro.f1clashcoach.core.engine.Progression
import com.leandro.f1clashcoach.core.model.Driver
import com.leandro.f1clashcoach.core.model.Garage
import com.leandro.f1clashcoach.core.model.Progress
import com.leandro.f1clashcoach.core.model.Rarity
import com.leandro.f1clashcoach.core.model.SeriesCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventFramesTest {
    @Test fun gpRacePopup() {
        val r = p("event_gp_popup")
        assertEquals(GameScreen.GP, r.screen)
        val gp = r.game!!.gp!!
        assertEquals("austin", gp.trackId)
        assertEquals(10, gp.rainChance)
        assertEquals("DesempenhoDelta", gp.sponsorTask)
        assertEquals(80_000L, gp.racePrize)
    }

    @Test fun restrictedLoadoutDoesNotOverwriteMainTeam() {
        val r = p("event_loadout")
        assertEquals(GameScreen.TEAM_CARS, r.screen)
        val g = r.game!!
        assertTrue(g.restricted)
        val start = Garage(teamScore = 1071)
        val synced = GameSync.apply(start, g, emptyList(), null, 0).garage
        assertEquals(1071, synced.teamScore)
        assertTrue(synced.parts.none { it.equipped })
    }

    @Test fun driverDetailWithUpgradeCost() {
        val r = p("event_detail")
        assertEquals(GameScreen.ASSET_DETAIL, r.screen)
        val a = r.game!!.asset!!
        assertEquals("Gzbriel BORTOLETO", a.name)
        assertEquals(Rarity.RARE, a.rarity)
        assertEquals("Júnior", a.minTier)
        assertEquals(462 to 400, a.cardsOwned to a.cardsNeeded)
        assertEquals(4_700_000L, a.upgradeCost)
        assertEquals(40, a.driverStats!!.overtaking)
        assertEquals(26, a.driverStats!!.tyreManagement)
        assertEquals(4, a.driverGain!!.overtaking)
        // Aplica ao piloto existente "G.Bortoleto".
        val g = Garage(drivers = listOf(Driver("1", "G.Bortoleto", progress = Progress(7, 413, 400))))
        val d = GameSync.apply(g, r.game!!, emptyList(), null, 0).garage.drivers.single()
        assertEquals(4_700_000, d.progress.upgradeCoins)
        assertEquals(Rarity.RARE, d.rarity)
        assertEquals(44, d.nextLevelStats!!.overtaking)
        assertEquals("Júnior", d.minTier)
    }

    @Test fun comparisonScreen() {
        val a = p("event_compare").game!!.asset!!
        assertEquals("Competidor", a.minTier)
        assertEquals(Rarity.RARE, a.rarity)
        assertEquals(null, a.driverStats)
    }

    @Test fun featuredList() {
        val f = p("event_featured").game!!.featured
        assertTrue(f.toString(), f.any { it.contains("Bearman") } && f.any { it.contains("Bortoleto") })
    }

    @Test fun roadToGrandSlam() {
        val plan = Progression.plan(Garage(series = 8, teamScore = 1071), SeriesCatalog.defaults, emptyList(), 3575)
        assertEquals(12, plan.target!!.number)
        assertTrue(plan.advice.first(), plan.advice.first().contains("faltam 379"))
        assertEquals(16_025, plan.steps.last().flagsMissing)
        assertFalse(plan.steps.isEmpty())
    }
}
