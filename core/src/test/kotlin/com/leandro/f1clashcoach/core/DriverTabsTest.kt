package com.leandro.f1clashcoach.core

import com.leandro.f1clashcoach.core.engine.GameSync
import com.leandro.f1clashcoach.core.model.Catalog
import com.leandro.f1clashcoach.core.model.Garage
import com.leandro.f1clashcoach.core.model.Rarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Telas reais: escalação, aba Lendários e aba Edições Especiais. */
class DriverTabsTest {
    @Test fun equippedPairIdentifiedEvenWithOcrErrors() {
        val read = RealFramesTest.p("team_setup").game!!
        val g = GameSync.apply(Garage(), read, emptyList(), null, 1L).garage
        val eq = g.drivers.filter { it.equipped }
        assertEquals(eq.toString(), 2, eq.size)
        val hill = eq.first { Catalog.sameName(it.name, "D.Hill") }
        assertEquals(Rarity.LEGENDARY, hill.rarity)
        assertEquals(1, hill.progress.level)
        assertEquals(41, hill.stats.overtaking)
        val lawson = eq.first { Catalog.sameName(it.name, "Lawson") }
        assertEquals(Rarity.EPIC, lawson.rarity)
        assertEquals(39, lawson.stats.defending) // OCR leu 62; a base corrige
    }

    @Test fun specialEditionsAreSeparate() {
        val read = RealFramesTest.p("drivers_special").game!!
        assertTrue(read.tiles.all { it.rarity == Rarity.SPECIAL && it.edition == "Escolhas do Paddock" })
        var g = GameSync.apply(Garage(), RealFramesTest.p("team_drivers_grid").game!!.let { r -> r.copy(tiles = r.tiles.map { it.copy(rarity = Rarity.COMMON) }) }, emptyList(), null, 1L).garage
        g = GameSync.apply(g, read, emptyList(), null, 2L).garage
        val piastri = g.drivers.filter { Catalog.sameName(it.name, "Piastri") }
        assertEquals(piastri.toString(), 2, piastri.size)
        assertEquals(1, piastri.count { it.rarity == Rarity.SPECIAL })
        assertTrue(Catalog.driverCollection(g).any { it.rarity == Rarity.SPECIAL && it.edition == "Escolhas do Paddock" })
    }

    @Test fun legendsTab() {
        val read = RealFramesTest.p("drivers_legends").game!!
        assertTrue(read.tiles.isNotEmpty())
        assertTrue(read.tiles.toString(), read.tiles.all { it.rarity == Rarity.LEGENDARY })
        val g = GameSync.apply(Garage(), read, emptyList(), null, 1L).garage
        assertTrue(g.drivers.all { it.rarity == Rarity.LEGENDARY && it.stats.total > 0 })
    }

    @Test fun ocrNames() {
        assertTrue(Catalog.sameName("O. Pizstri", "Piastri"))
        assertTrue(Catalog.sameName("G.Fisichellz", "Fisichella"))
        assertTrue(Catalog.sameName("D. HilI", "D.Hill"))
        assertTrue(!Catalog.sameName("D. Hill", "G.Hill"))
        assertTrue(!Catalog.sameName("Hamilton", "Hadjar"))
    }
}
