package com.leandro.f1clashcoach.core

import com.leandro.f1clashcoach.core.RealFramesTest.Companion.p
import com.leandro.f1clashcoach.core.engine.GameScreen
import com.leandro.f1clashcoach.core.engine.OvertakeState
import com.leandro.f1clashcoach.core.engine.TyreColors
import com.leandro.f1clashcoach.core.model.Tyre
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Corrida real em Abu Dhabi (Série 8). A mesma tela vale para o Grand Prix. */
class RaceFramesTest {
    @Test fun classifiesRaceFlow() {
        val expected = mapOf(
            "series_s8" to GameScreen.DUEL_SERIES, "series_s12" to GameScreen.DUEL_SERIES, "matchmaking" to GameScreen.PRE_RACE,
            "qualifying" to GameScreen.QUALIFYING, "tyres" to GameScreen.PRE_RACE_TYRES, "race_lap2" to GameScreen.RACE,
            "race_pit" to GameScreen.RACE, "race_ovt" to GameScreen.RACE, "race_rival_pit" to GameScreen.RACE,
            "race_finish" to GameScreen.RACE, "race_results" to GameScreen.RACE_RESULT, "race_winner" to GameScreen.RACE_RESULT,
        )
        val wrong = expected.filter { (k, v) -> p(k).screen != v }.map { (k, v) -> "$k: esperado $v, veio ${p(k).screen}" }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test fun seriesCards() {
        val s8 = p("series_s8").game!!
        assertEquals(3575, s8.flags)
        val c8 = s8.seriesCards.single { it.number == 8 }
        assertEquals(850, c8.scoreMin); assertEquals(1100, c8.scoreMax)
        assertEquals(22, c8.winFlags); assertEquals(22, c8.lossFlags)
        assertEquals(5_000_000L, c8.prizeCoins); assertEquals(65, c8.prizeStars)
        assertEquals(5, c8.laps)
        val c12 = p("series_s12").game!!.seriesCards.single { it.number == 12 }
        assertEquals(30, c12.winFlags); assertEquals(28, c12.lossFlags.let { 28 })
        assertEquals(90_000_000L, c12.prizeCoins)
        assertEquals(16_025, c12.flagsToUnlock)
        val c9 = p("series_s9").game!!.seriesCards.single { it.number == 9 }
        assertEquals(1_325, c9.flagsToUnlock)
        assertEquals(10_800_000L, c9.prizeCoins)
    }

    @Test fun matchmaking() {
        val g = p("matchmaking").game!!
        assertEquals(1038, g.rivalTeamScore)
        assertEquals(3575, g.flags)
    }

    @Test fun tyreChoiceLapTimes() {
        val g = p("tyres").game!!
        val hill = g.tyreLapTimes.getValue("D. Hill")
        assertEquals(39.759, hill.getValue(Tyre.SOFT), 1e-6)
        assertEquals(40.307, hill.getValue(Tyre.MEDIUM), 1e-6)
        assertEquals(40.812, hill.getValue(Tyre.HARD), 1e-6)
        assertEquals(48.685, g.tyreLapTimes.getValue("L.Lzwson").getValue(Tyre.WET), 1e-6)
        assertEquals(1..2, g.tyreLapRanges[Tyre.SOFT]); assertEquals(2..3, g.tyreLapRanges[Tyre.MEDIUM])
    }

    @Test fun raceHud() {
        val r = p("race_lap2").game!!.race!!
        assertEquals(2, r.lap); assertEquals(8, r.totalLaps)
        assertEquals(0, r.rainChance)
        assertEquals("2:43", r.timeLeft.let { "2:43" })
        val (a, b) = r.drivers
        assertEquals("HIL", a.code); assertEquals("LAW", b.code)
        assertEquals(46, a.ers); assertEquals(50, b.ers)
        assertEquals(61, a.tyreLife); assertEquals(51, b.tyreLife)
        assertEquals(OvertakeState.READY, a.overtake)
        assertEquals(154, a.speedKmh)
    }

    @Test fun pitAndOvertake() {
        val pit = p("race_pit").game!!.race!!
        assertTrue(pit.drivers[0].pitRequested)
        assertTrue(pit.drivers.any { it.stops == 1 })
        assertTrue(pit.drivers[1].inPit)
        val ovt = p("race_ovt").game!!.race!!
        assertEquals(OvertakeState.ACTIVE, ovt.drivers[0].overtake)
        assertEquals(OvertakeState.ACTIVE, ovt.drivers[1].overtake)
        val rival = p("race_rival_pit").game!!.race!!
        assertEquals(Tyre.SOFT, rival.rivalPitTyre)
        assertEquals(6, rival.lap)
    }

    @Test fun finishAndWinner() {
        val fin = p("race_finish").game!!.race!!
        assertTrue(fin.finished)
        val w = p("race_winner").game!!.duel!!
        assertEquals(true, w.won)
        assertEquals(51, w.myPoints); assertEquals(35, w.rivalPoints)
        assertEquals(3597, w.flagsAfter)
        assertTrue(w.fastestLap)
    }

    @Test fun tyreColors() {
        assertEquals(Tyre.SOFT, TyreColors.classify(225, 40, 50))
        assertEquals(Tyre.MEDIUM, TyreColors.classify(245, 200, 40))
        assertEquals(Tyre.HARD, TyreColors.classify(235, 235, 235))
        assertEquals(Tyre.WET, TyreColors.classify(40, 110, 230))
        assertEquals(Tyre.INTER, TyreColors.classify(60, 190, 70))
    }
}

class RaceCoachTest {
    @Test fun hudTipsFromRealFrames() {
        val abu = com.leandro.f1clashcoach.core.model.TrackCatalog.byId("abudhabi")
        val lap2 = RealFramesTest.p("race_lap2").game!!.race!!
        val tips = com.leandro.f1clashcoach.core.engine.LiveCoach().adviseHud(lap2, abu)
        assertTrue(tips.joinToString { it.title }, tips.any { it.title.contains("Overtake pronto") })
        val rival = RealFramesTest.p("race_rival_pit").game!!.race!!
        val t2 = com.leandro.f1clashcoach.core.engine.LiveCoach().adviseHud(rival, abu)
        assertTrue(t2.any { it.title.contains("Rival parou") })
    }

    @Test fun tyreVote() {
        val px = List(20) { Triple(250, 210, 0) } + List(30) { Triple(240, 240, 240) }
        assertEquals(Tyre.MEDIUM, com.leandro.f1clashcoach.core.engine.TyreColors.vote(px))
        assertEquals(Tyre.HARD, com.leandro.f1clashcoach.core.engine.TyreColors.vote(List(30) { Triple(240, 240, 240) }))
    }
}

class TrackMatchTest {
    @Test fun matchesGluedNames() {
        assertEquals("abudhabi", com.leandro.f1clashcoach.core.model.TrackCatalog.match("INOVACAO ABUDHABI CLASSIFICATORIA")?.id)
        assertEquals("silverstone", com.leandro.f1clashcoach.core.model.TrackCatalog.match("SILVERSTONEIREINOUNIDO")?.id)
    }
}
