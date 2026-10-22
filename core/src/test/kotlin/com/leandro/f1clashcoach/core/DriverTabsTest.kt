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

class DedupeTest {
    private fun d(n: String, r: com.leandro.f1clashcoach.core.model.Rarity, lvl: Int = 1, eq: Boolean = false) =
        com.leandro.f1clashcoach.core.model.Driver(n + r + lvl, n, rarity = r, progress = com.leandro.f1clashcoach.core.model.Progress(lvl, 3, 4), equipped = eq)

    @Test fun mergesOcrVariantsSavedBefore() {
        val E = Rarity.EPIC; val C = Rarity.COMMON; val U = Rarity.UNKNOWN
        val g = Garage(drivers = listOf(
            d("L. LanSon", E), d("L. LaSon", E), d("L.LawVSon", E, eq = true), d("L. LawWSon", E),
            d("L. Harmilton", C, 5), d("L.Harilton", C, 5), d("L. Hamnilton", U), d("L. Hamilton", Rarity.RARE),
            d("M. Wetbber", U, 2), d("J. Herbert", U), d("N. Hukenberg", C, 5), d("N. Hulkenberg", C, 5),
            d("D. Hill", U), d("G. Hill", U),
        ))
        val out = Catalog.dedupe(g).drivers
        fun count(s: String) = out.count { Catalog.sameName(it.name, s) }
        assertEquals(out.toString(), 1, count("Lawson"))
        assertTrue(out.first { Catalog.sameName(it.name, "Lawson") }.equipped)
        assertEquals("L. Lawson", out.first { Catalog.sameName(it.name, "Lawson") }.name)
        assertEquals(2, count("Hamilton")) // Comum + Rara; a desconhecida some
        assertEquals(1, count("Hulkenberg"))
        assertEquals(Rarity.LEGENDARY, out.first { Catalog.sameName(it.name, "Webber") }.rarity)
        assertEquals(1, out.count { Catalog.sameName(it.name, "D.Hill") })
        assertEquals(1, out.count { Catalog.sameName(it.name, "G.Hill") })
        assertTrue(out.any { it.name.contains("Herbert") })
    }

    @Test fun gameVideoGivesGameCounts() {
        // passa todos os quadros da aba Pilotos do vídeo do jogo pela leitura (cor por quadro já resolvida pelo app)
        var g = Garage()
        for (k in listOf("team_drivers_top", "team_drivers_grid", "drivers_grid_rare", "drivers_legends", "drivers_special", "team_setup")) {
            val r = RealFramesTest.p(k).game ?: continue
            g = com.leandro.f1clashcoach.core.engine.GameSync.apply(g, r, emptyList(), null, 1L).garage
        }
        val legends = g.drivers.count { it.rarity == Rarity.LEGENDARY }
        val specials = g.drivers.count { it.rarity == Rarity.SPECIAL }
        assertTrue("lendários=$legends ${g.drivers.map { it.name + ":" + it.rarity }}", legends in 5..7)
        assertEquals(2, specials)
        assertEquals(g.drivers.size, g.drivers.map { Catalog.driverKey(it.name) + it.rarity + it.edition }.toSet().size)
    }
}

class CarTotalsTest {
    @Test fun pitStopTimeRead() {
        val t = RealFramesTest.p("team_cars_top").game!!.carTotals!!
        assertEquals(4.34, t.pitStop, 0.001)
        assertEquals(148, t.cornering)
    }
}

class MergedLinesTest {
    /** Junta cada rótulo com o número à direita/esquerda na mesma linha, como o ML Kit faz às vezes. */
    private fun merged(key: String): List<com.leandro.f1clashcoach.core.engine.TextLine> {
        val ls = RealFramesTest.frames.getValue(key).toMutableList()
        val labels = listOf("Velocidade", "Curvas", "Unidade", "Qualifica", "Tempo", "Modo", "Ultrap", "Defes", "Inicio", "Gerenc")
        val out = ArrayList<com.leandro.f1clashcoach.core.engine.TextLine>(); val used = HashSet<Int>()
        ls.forEachIndexed { i, l ->
            if (i in used) return@forEachIndexed
            if (labels.any { l.text.replace('z', 'a').startsWith(it, true) }) {
                val cy = (l.top + l.bottom) / 2
                val nums = ls.withIndex().filter { (j, o) -> j != i && j !in used && Math.abs((o.top + o.bottom) / 2 - cy) < 0.01f && o.text.trim().matches(Regex("""[\d.,]+s?""")) }
                val r = nums.filter { it.value.left > l.left }.minByOrNull { it.value.left - l.right }
                val lf = nums.filter { it.value.right < l.left + 0.01f }.minByOrNull { l.left - it.value.right }
                var t = l.text; var left = l.left; var right = l.right
                r?.let { t = "$t ${it.value.text}"; right = it.value.right; used += it.index }
                lf?.let { t = "${it.value.text} $t"; left = it.value.left; used += it.index }
                out += com.leandro.f1clashcoach.core.engine.TextLine(t, left, l.top, right, l.bottom); used += i
            } else out += l
        }
        return out.filterIndexed { _, _ -> true }
    }

    @Test fun carTotalsWithMergedLines() {
        val t = com.leandro.f1clashcoach.core.engine.ScreenReader.parse(merged("team_cars_top")).game!!.carTotals!!
        assertEquals(111, t.speed); assertEquals(148, t.cornering); assertEquals(77, t.powerUnit)
        assertEquals(4.34, t.pitStop, 0.001); assertEquals(36, t.overtakeMode)
    }

    @Test fun driverTableWithMergedLines() {
        val pair = com.leandro.f1clashcoach.core.engine.ScreenReader.parse(merged("team_setup")).game!!.driverPair
        assertEquals(41, pair[0].second.overtaking); assertEquals(59, pair[1].second.overtaking)
        assertEquals(47, pair[0].second.tyreManagement); assertEquals(54, pair[1].second.tyreManagement)
    }
}
