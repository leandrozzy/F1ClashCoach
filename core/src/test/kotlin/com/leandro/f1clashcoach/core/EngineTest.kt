package com.leandro.f1clashcoach.core

import com.leandro.f1clashcoach.core.ai.ChatTurn
import com.leandro.f1clashcoach.core.ai.ClaudeAdvisor
import com.leandro.f1clashcoach.core.ai.CoachPrompts
import com.leandro.f1clashcoach.core.ai.GeminiAdvisor
import com.leandro.f1clashcoach.core.engine.DriverLive
import com.leandro.f1clashcoach.core.engine.GameScreen
import com.leandro.f1clashcoach.core.engine.HistoryAnalyzer
import com.leandro.f1clashcoach.core.engine.LiveCoach
import com.leandro.f1clashcoach.core.engine.RaceContext
import com.leandro.f1clashcoach.core.engine.RaceSnapshot
import com.leandro.f1clashcoach.core.engine.RaceStrategist
import com.leandro.f1clashcoach.core.engine.ScreenReader
import com.leandro.f1clashcoach.core.engine.SetupOptimizer
import com.leandro.f1clashcoach.core.engine.TextLine
import com.leandro.f1clashcoach.core.engine.TipLevel
import com.leandro.f1clashcoach.core.engine.UpgradePlanner
import com.leandro.f1clashcoach.core.engine.WearModel
import com.leandro.f1clashcoach.core.model.CarStats
import com.leandro.f1clashcoach.core.model.Driver
import com.leandro.f1clashcoach.core.model.DriverStats
import com.leandro.f1clashcoach.core.model.Garage
import com.leandro.f1clashcoach.core.model.Part
import com.leandro.f1clashcoach.core.model.PartSlot
import com.leandro.f1clashcoach.core.model.Progress
import com.leandro.f1clashcoach.core.model.RaceRecord
import com.leandro.f1clashcoach.core.model.TrackCatalog
import com.leandro.f1clashcoach.core.model.Tyre
import com.leandro.f1clashcoach.core.model.TyreObservation
import com.leandro.f1clashcoach.core.model.Weather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    private val monza = TrackCatalog.byId("monza")!!
    private val monaco = TrackCatalog.byId("monaco")!!

    private fun garage() = Garage(
        parts = listOf(
            Part("e1", PartSlot.ENGINE, "Motor Rápido", stats = CarStats(speed = 30, cornering = 5, powerUnit = 20), equipped = true,
                progress = Progress(5, 20, 20, 5000)),
            Part("e2", PartSlot.ENGINE, "Motor Curva", stats = CarStats(speed = 8, cornering = 35, powerUnit = 10, qualifying = 10),
                progress = Progress(5, 3, 20, 5000)),
            Part("b1", PartSlot.BRAKES, "Freio A", stats = CarStats(cornering = 20, qualifying = 5, pitStop = 0.5), equipped = true,
                progress = Progress(4, 10, 10, 2000), nextLevelStats = CarStats(cornering = 24, qualifying = 6, pitStop = 0.45)),
        ),
        drivers = listOf(
            Driver("d1", "Ultrapassador", stats = DriverStats(overtaking = 60, defending = 30, qualifying = 20, raceStart = 30, tyreManagement = 30), equipped = true),
            Driver("d2", "Classificador", stats = DriverStats(overtaking = 20, defending = 45, qualifying = 60, raceStart = 50, tyreManagement = 30), equipped = true),
            Driver("d3", "Reserva", stats = DriverStats(10, 10, 10, 10, 10)),
        ),
        coins = 6000,
    )

    @Test fun setupDependsOnTrack() {
        assertEquals("e1", SetupOptimizer.optimize(garage(), monza).choices.first { it.slot == PartSlot.ENGINE }.best?.id)
        val m = SetupOptimizer.optimize(garage(), monaco)
        assertEquals("e2", m.choices.first { it.slot == PartSlot.ENGINE }.best?.id)
        assertTrue(m.changes.any { it.contains("Motor Curva") })
        assertEquals(setOf("d1", "d2"), m.drivers.map { it.id }.toSet())
    }

    @Test fun strategyAvoidsBlowingTyres() {
        val plans = RaceStrategist().bestPlans(RaceContext(monza, laps = 8, tyreManagement = 40))
        assertTrue(plans.isNotEmpty())
        plans.forEach { assertTrue(it.endWear <= 1.0) }
        // 8 voltas não dá de macio sem parar.
        assertFalse(plans.first().stints.size == 1 && plans.first().stints[0].tyre == Tyre.SOFT && plans.first().stints[0].pace != com.leandro.f1clashcoach.core.model.DrivingPace.CONSERVE && plans.first().endWear > 1)
    }

    @Test fun strategyUsesRainTyres() {
        val weather = List(4) { Weather.DRY } + List(4) { Weather.HEAVY_RAIN }
        val best = RaceStrategist().bestPlans(RaceContext(monza, laps = 8, weatherByLap = weather)).first()
        assertTrue(best.summary(), best.stints.last().tyre == Tyre.WET || best.stints.last().tyre == Tyre.INTER)
        assertTrue(best.stints.first().tyre.isDry)
    }

    @Test fun liveCoachCallsBoxInRain() {
        val tips = LiveCoach().advise(
            RaceSnapshot(lap = 3, totalLaps = 6, weather = Weather.LIGHT_RAIN, drivers = listOf(DriverLive(tyre = Tyre.MEDIUM, tyreLife = 70))),
            monza,
        )
        assertEquals(TipLevel.URGENT, tips.first().level)
        assertTrue(tips.first().title.contains("Intermediário"))
    }

    @Test fun liveCoachLowTyre() {
        val tips = LiveCoach().advise(
            RaceSnapshot(lap = 2, totalLaps = 8, drivers = listOf(DriverLive(tyre = Tyre.SOFT, tyreLife = 10))), monza,
        )
        assertTrue(tips.any { it.title.contains("BOX") })
    }

    @Test fun wearModelLearns() {
        val obs = List(10) { TyreObservation("monza", Tyre.SOFT, lapsOnTyre = 2.0, wearPercent = 20.0) }
        val learned = WearModel(obs).wearPerLap(monza, Tyre.SOFT, 0)
        val prior = WearModel().wearPerLap(monza, Tyre.SOFT, 0)
        assertTrue(learned < prior)
        assertEquals(10, WearModel(obs).samples("monza", Tyre.SOFT))
    }

    @Test fun readsRaceScreen() {
        val p = ScreenReader.parse(listOf(
            TextLine("LAP 3/7", 0.45f, 0.02f, 0.55f, 0.06f),
            TextLine("P4", 0.05f, 0.3f, 0.08f, 0.33f), TextLine("P7", 0.05f, 0.4f, 0.08f, 0.43f),
            TextLine("62%", 0.1f, 0.8f, 0.15f, 0.85f), TextLine("38%", 0.6f, 0.8f, 0.65f, 0.85f),
            TextLine("MEDIUM", 0.1f, 0.75f, 0.2f, 0.78f), TextLine("SOFT", 0.6f, 0.75f, 0.7f, 0.78f),
            TextLine("Monza", 0.8f, 0.02f, 0.9f, 0.05f),
        ))
        assertEquals(GameScreen.RACE, p.screen)
        assertEquals(3, p.lap); assertEquals(7, p.totalLaps)
        assertEquals(listOf(4, 7), p.positions)
        assertEquals(listOf(62, 38), p.percents)
        assertEquals("monza", p.track?.id)
        val snap = p.toSnapshot()
        assertEquals(2, snap.drivers.size)
        assertEquals(Tyre.SOFT, snap.drivers[1].tyre)
    }

    @Test fun readsPartScreen() {
        val p = ScreenReader.parse(listOf(
            TextLine("Turbo Jet", 0.3f, 0.1f, 0.6f, 0.15f),
            TextLine("ENGINE", 0.3f, 0.16f, 0.5f, 0.2f),
            TextLine("LEVEL 6", 0.3f, 0.2f, 0.4f, 0.24f),
            TextLine("Speed 34", 0.1f, 0.5f, 0.3f, 0.54f), TextLine("Cornering 12", 0.1f, 0.55f, 0.3f, 0.59f),
            TextLine("Power Unit 21", 0.1f, 0.6f, 0.3f, 0.64f), TextLine("Qualifying 8", 0.1f, 0.65f, 0.3f, 0.69f),
            TextLine("Pit Stop Time 0.62", 0.1f, 0.7f, 0.3f, 0.74f), TextLine("14/30", 0.4f, 0.8f, 0.5f, 0.84f),
        ))
        assertEquals(GameScreen.PARTS, p.screen)
        assertEquals(PartSlot.ENGINE, p.slot)
        assertEquals(6, p.level)
        val s = assertNotNull(p.carStats).let { p.carStats!! }
        assertEquals(34, s.speed); assertEquals(12, s.cornering); assertEquals(21, s.powerUnit); assertEquals(0.62, s.pitStop, 1e-9)
        assertEquals(14 to 30, p.cards)
        assertEquals("Turbo Jet", p.itemName)
    }

    @Test fun resultScreen() {
        val p = ScreenReader.parse(listOf(TextLine("VICTORY"), TextLine("+32 TROPHIES")))
        assertEquals(GameScreen.RACE_RESULT, p.screen)
        assertEquals(true, p.won)
    }

    @Test fun upgradePlanRespectsBudget() {
        val plan = UpgradePlanner.plan(garage())
        assertTrue(plan.doNow.sumOf { it.coins } <= 6000)
        assertTrue(plan.doNow.isNotEmpty())
        assertTrue(plan.collectCards.any { it.itemId == "e2" })
    }

    @Test fun historyInsights() {
        val recs = (1..12).map { i ->
            RaceRecord("r$i", i.toLong(), "monaco", gridPositions = listOf(8), finishPositions = listOf(5), won = i % 3 == 0)
        }
        val r = HistoryAnalyzer.analyze(recs)
        assertEquals(12, r.races); assertEquals(4, r.wins)
        assertTrue(r.insights.any { it.contains("Qualifying") })
        assertTrue(r.insights.any { it.contains("Mônaco") })
    }

    @Test fun promptContextHasData() {
        val ctx = CoachPrompts.accountContext(garage(), emptyList(), monza)
        assertTrue(ctx.contains("Motor Rápido"))
        assertTrue(ctx.contains("Setup calculado para Itália"))
    }

    @Test fun historyNormalization() {
        val h = ClaudeAdvisor.normalizeHistory(listOf(ChatTurn(false, "oi"), ChatTurn(true, "a"), ChatTurn(true, "b"), ChatTurn(false, "c"), ChatTurn(true, "d")))
        assertEquals(listOf(ChatTurn(true, "a\n\nb"), ChatTurn(false, "c")), h)
    }

    @Test fun parsesGemini() {
        val raw = """{"candidates":[{"content":{"parts":[{"text":"Use pneu "},{"text":"médio."}]},
            "groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://x.com/a","title":"Guia"}}]}}]}"""
        val a = GeminiAdvisor.parse(raw)
        assertEquals("Use pneu médio.", a.text)
        assertEquals("Guia", a.sources.single().title)
    }
}
