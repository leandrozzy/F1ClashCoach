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
