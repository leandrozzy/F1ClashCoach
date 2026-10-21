package com.leandro.f1clashcoach.core

import com.leandro.f1clashcoach.core.engine.GameSync
import com.leandro.f1clashcoach.core.model.Catalog
import com.leandro.f1clashcoach.core.model.Garage
import com.leandro.f1clashcoach.core.model.PartSlot
import com.leandro.f1clashcoach.core.model.Rarity
import com.leandro.f1clashcoach.core.model.RarityColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    @Test fun loadsSeason2026() {
        assertEquals(46, Catalog.parts.size)
        assertEquals(88, Catalog.drivers.size)
        assertEquals(22, Catalog.drivers.count { it.rarity == Rarity.LEGENDARY })
        assertEquals(3, Catalog.findDrivers("F. Colapinto").size)
        assertEquals(1, Catalog.findDrivers("A. Senna").size)
        assertEquals("Air Channel", Catalog.findPart("AirChannel")!!.name)
        assertEquals(PartSlot.BATTERY, Catalog.findPart("Spare Pack")!!.slot)
        assertEquals(8, Catalog.levelFromCardsNeeded(1000))
        assertEquals(1, Catalog.levelFromCardsNeeded(4))
        val v = Catalog.findDriver("M. Verstappen", Rarity.EPIC)!!
        assertEquals(v to 3, Catalog.matchDriverStats("M.Verstappen", v.at(3)!!))
        assertEquals(3_000L, Catalog.partCoinsToNext(1, 1))
        assertTrue(Catalog.findDrivers("D. Hill").single().name == "D.Hill")
    }

    @Test fun rarityByCardColor() {
        fun many(r: Int, g: Int, b: Int) = List(12) { Triple(r, g, b) }
        assertEquals(Rarity.COMMON, RarityColors.classify(many(40, 120, 235)))
        assertEquals(Rarity.RARE, RarityColors.classify(many(250, 120, 30)))
        assertEquals(Rarity.EPIC, RarityColors.classify(many(130, 50, 220)))
        assertEquals(Rarity.UNKNOWN, RarityColors.classify(many(120, 120, 125)))
    }

    @Test fun syncFillsLevelsStatsAndCosts() {
        val read = RealFramesTest.p("team_drivers_grid").game!!
        val dup = read.tiles.map { it.name }
        val g = GameSync.apply(Garage(), read.copy(tiles = read.tiles.map { it.copy(rarity = Rarity.COMMON) }), emptyList(), null, 1L).garage
        val col = g.drivers.first { it.name.contains("Colapinto") }
        assertEquals(Rarity.COMMON, col.rarity)
        assertEquals(8, col.progress.level)
        assertTrue(col.stats.total > 0)
        assertNotNull(col.nextLevelStats)
        assertTrue(col.progress.upgradeCoins > 0)
        // a mesma carta em outra raridade vira outro piloto
        val rare = read.tiles.first { it.name.contains("Colapinto") }.copy(rarity = Rarity.RARE, cardsNeeded = 20, cardsOwned = 461, level = 3)
        val g2 = GameSync.apply(g, read.copy(tiles = listOf(rare)), emptyList(), null, 2L).garage
        assertEquals(2, g2.drivers.count { it.name.contains("Colapinto") })
        val items = Catalog.driverCollection(g2)
        assertTrue(items.any { it.name == "Colapinto" && it.rarity == Rarity.RARE && it.owned && it.level == 3 })
        assertTrue(items.any { it.name == "Senna" && !it.owned })
        assertTrue(dup.isNotEmpty())
    }
}
