package com.leandro.f1clashcoach.core

import com.leandro.f1clashcoach.core.engine.Boosters
import com.leandro.f1clashcoach.core.engine.LiveWear
import com.leandro.f1clashcoach.core.model.TrackCatalog
import com.leandro.f1clashcoach.core.model.Tyre
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceTruthTest {
    private val ranges = mapOf(Tyre.SOFT to 1..2, Tyre.MEDIUM to 2..3, Tyre.HARD to 3..5)
    private val times = mapOf(Tyre.SOFT to 39.7, Tyre.MEDIUM to 40.3, Tyre.HARD to 40.8)

    @Test fun pitFromMeasuredWear() {
        val w = LiveWear()
        // Medium perdendo 30% por volta, 8 voltas
        w.observe(0, 1, Tyre.MEDIUM, 100); w.observe(0, 2, Tyre.MEDIUM, 70); w.observe(0, 3, Tyre.MEDIUM, 40)
        assertEquals(30.0, w.measuredRate(0)!!, 0.01)
        val c = w.call(0, 3, 8, Tyre.MEDIUM, 40, ranges, times)!!
        assertTrue(c.measured)
        assertEquals(3, c.pitAtEndOfLap) // 40% - 10% = 1 volta: para no fim desta
        assertEquals(Tyre.HARD, c.nextTyre) // faltam 5 voltas: só o duro cobre
        assertTrue(c.urgent)
    }

    @Test fun noStopWhenTyreLasts() {
        val w = LiveWear()
        w.observe(1, 4, Tyre.HARD, 90); w.observe(1, 5, Tyre.HARD, 75)
        val c = w.call(1, 5, 6, Tyre.HARD, 75, ranges, times)!!
        assertNull(c.pitAtEndOfLap)
        assertTrue(c.text.contains("sem parada"))
    }

    @Test fun usesGameRangeBeforeFirstLap() {
        val c = LiveWear().call(0, 1, 8, Tyre.SOFT, 100, ranges, times)!!
        assertTrue(!c.measured)
        assertNotNull(c.pitAtEndOfLap)
    }

    @Test fun boostersRespectCountsAndRules() {
        val counts = mapOf("Cogumelo" to 19, "Aguiz" to 9, "Transmisszo Mzxima" to 7, "Meia-noite" to 5, "Chama Eterna" to 1, "Desconhecido" to 3)
        val plan = Boosters.plan(counts, TrackCatalog.tracks.first(), stops = 1, winPct = 60)
        assertEquals(4, plan.slots.size)
        // nunca usa mais do que tem
        plan.used.forEach { (n, u) -> assertTrue(n, u <= (Boosters.owned(counts).first { it.name == n }.count)) }
        // depois do pit, reforço de largada não serve
        val after = plan.slots.filter { it.stint == 1 }.map { Boosters.find(it.booster)!! }
        assertTrue(after.none { it.effects.keys.singleOrNull() == com.leandro.f1clashcoach.core.engine.BoostStat.RACE_START })
        assertEquals("Transmissão Máxima", Boosters.find("Transmisszo Mzxima")!!.name)
        assertTrue(plan.lines.first().startsWith("Largada"))
    }
}

class RangePlannerTest {
    @Test fun plansFitGameRanges() {
        val r = mapOf(Tyre.SOFT to 1..2, Tyre.MEDIUM to 2..3, Tyre.HARD to 3..5)
        val t = mapOf(Tyre.SOFT to 39.76, Tyre.MEDIUM to 40.31, Tyre.HARD to 40.81)
        val p = com.leandro.f1clashcoach.core.engine.RangePlanner.plans(8, r, t, pitLoss = 8.0)
        assertTrue(p.isNotEmpty())
        p.forEach { pl -> assertEquals(8, pl.stints.sumOf { it.second }); pl.stints.forEach { (ty, n) -> assertTrue(n <= r.getValue(ty).last) } }
        assertTrue(p.first().stops >= 1) // 8 voltas: nenhum composto aguenta sozinho
    }
}

class BoosterScreenTest {
    @Test fun readsTwoLineNamesAndCanonicalizes() {
        val a = RealFramesTest.p("boosters_a").game!!.boosters
        assertEquals(125, a["Autocontrole"]); assertEquals(118, a["Lastro"]); assertEquals(38, a["Instinto"])
        assertEquals(57, a["Sustentação negativa"]); assertEquals(18, a["Hex"])
        assertTrue(a.keys.toString(), a.keys.none { it.equals("negativa", true) || it.startsWith("Sustenta") && it != "Sustentação negativa" })
        val b = RealFramesTest.p("boosters_b").game!!.boosters
        assertEquals(20, b["Tire Water"]); assertEquals(27, b["Combustível de ghoul"])
        // tudo o que foi lido existe no catálogo de 45
        (a.keys + b.keys).forEach { assertNotNull(it, Boosters.find(it)) }
        assertEquals(45, Boosters.all.size)
        assertEquals(45, Boosters.all.map { it.name }.toSet().size)
    }
}

class AdvisorTest {
    @Test fun buildsAutomaticCardsFromRealScreens() {
        var g = com.leandro.f1clashcoach.core.model.Garage()
        for (k in listOf("team_cars_top", "team_cars_grid", "team_drivers_grid", "team_setup", "boosters_a", "boosters_b")) {
            RealFramesTest.p(k).game?.let { g = com.leandro.f1clashcoach.core.engine.GameSync.apply(g, it, emptyList(), null, 1L).garage }
        }
        g = g.copy(coins = 46_672_703)
        val cards = com.leandro.f1clashcoach.core.engine.Advisor.cards(g, emptyList(), emptyList(), emptyList(), null,
            com.leandro.f1clashcoach.core.engine.ClubState(), com.leandro.f1clashcoach.core.model.SeriesCatalog.defaults, 3575, emptyList(), 0, 1L)
        val up = cards.first { it.id == "up" }
        assertTrue(up.lines.toString(), up.missing == null && up.lines.isNotEmpty())
        val boost = cards.first { it.id == "boost" }
        assertTrue(boost.lines.toString(), boost.lines.first().startsWith("Largada"))
        assertTrue(cards.first { it.id == "club" }.missing != null)
        // tudo que vai para "fazer agora" tem custo real e cabe no saldo
        val plan = com.leandro.f1clashcoach.core.engine.UpgradePlanner.plan(g)
        assertTrue(plan.doNow.all { it.coins > 0 })
        assertTrue(plan.doNow.sumOf { it.coins.toLong() } <= g.coins)
    }
}
