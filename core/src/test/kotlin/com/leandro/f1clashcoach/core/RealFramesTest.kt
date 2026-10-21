package com.leandro.f1clashcoach.core

import com.leandro.f1clashcoach.core.engine.GameScreen
import com.leandro.f1clashcoach.core.engine.ParsedScreen
import com.leandro.f1clashcoach.core.engine.ScreenReader
import com.leandro.f1clashcoach.core.engine.TextLine
import com.leandro.f1clashcoach.core.model.PartSlot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telas reais do F1 Clash (vídeo do usuário, OCR de baixa qualidade de propósito:
 * se funciona com ele, funciona melhor ainda com o ML Kit no celular).
 */
class RealFramesTest {
    companion object {
        val frames: Map<String, List<TextLine>> by lazy {
            val raw = RealFramesTest::class.java.getResource("/frames.json")!!.readText()
            Json.parseToJsonElement(raw).jsonObject.mapValues { (_, v) ->
                v.jsonArray.map { l ->
                    val a = l.jsonArray
                    TextLine(a[0].jsonPrimitive.content, a[1].jsonPrimitive.content.toFloat(), a[2].jsonPrimitive.content.toFloat(),
                        a[3].jsonPrimitive.content.toFloat(), a[4].jsonPrimitive.content.toFloat())
                }
            }
        }
        fun p(name: String, lastSlot: PartSlot? = null): ParsedScreen = ScreenReader.parse(frames.getValue(name), lastSlot)
    }

    @Test fun classifiesEveryScreen() {
        val expected = mapOf(
            "home" to GameScreen.HOME, "store_featured" to GameScreen.STORE, "store_crates" to GameScreen.STORE,
            "objectives" to GameScreen.OBJECTIVES, "objectives_weekly" to GameScreen.OBJECTIVES,
            "team_drivers_top" to GameScreen.TEAM_DRIVERS, "team_drivers_grid" to GameScreen.TEAM_DRIVERS,
            "team_drivers_table" to GameScreen.TEAM_DRIVERS, "team_cars_top" to GameScreen.TEAM_CARS,
            "team_cars_grid" to GameScreen.TEAM_CARS, "team_cars_grid2" to GameScreen.TEAM_CARS, "boosters" to GameScreen.BOOSTERS,
            "club_members" to GameScreen.CLUB, "club_league" to GameScreen.CLUB, "pit_pass" to GameScreen.PIT_PASS,
            "race_menu" to GameScreen.RACE_MENU, "gp_detail" to GameScreen.GP, "gp_board" to GameScreen.GP,
        )
        val wrong = expected.filter { (k, v) -> p(k).screen != v }.map { (k, v) -> "$k: esperado $v, veio ${p(k).screen}" }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test fun topBarCurrencies() {
        val g = p("home").game!!
        assertEquals(46_672_703L, g.coins)
        assertEquals(1_200L, g.bucks)
        assertTrue(g.freeCrateReady)
        assertEquals("Exibic2o 71", g.seasonName)
    }

    @Test fun carTotalsAndEquippedParts() {
        val g = p("team_cars_top").game!!
        assertEquals(1071, g.teamScore)
        val t = g.carTotals!!
        assertEquals(111, t.speed); assertEquals(148, t.cornering); assertEquals(77, t.powerUnit)
        assertEquals(106, t.qualifying); assertEquals(4.34, t.pitStop, 1e-9); assertEquals(36, t.overtakeMode)
        assertEquals(7, g.equippedParts.size)
        val bySlot = g.equippedParts.associate { it.slot to it.name }
        assertEquals("Scramble", bySlot[PartSlot.BRAKES])
        assertEquals("Charge Core", bySlot[PartSlot.BATTERY])
        assertEquals(48 to 200, g.equippedParts.first { it.name == "Scramble" }.let { it.cardsOwned to it.cardsNeeded })
    }

    @Test fun partsGridWithSlots() {
        val g = p("team_cars_grid").game!!
        val byName = g.tiles.associateBy { it.name }
        val wobble = byName.getValue("Wobble")
        assertEquals(PartSlot.REAR_WING, wobble.slot)
        assertEquals(506 to 400, wobble.cardsOwned to wobble.cardsNeeded)
        assertTrue(wobble.canUpgrade)
        assertEquals(PartSlot.FRONT_WING, byName.getValue("Zephyr").slot)
        assertTrue(byName.getValue("Tailwind").featured)
        assertFalse(byName.containsKey("Starter"))
        // Peças sem cabeçalho visível herdam o último slot visto.
        val g2 = p("team_cars_grid2", lastSlot = PartSlot.SUSPENSION).game!!
        assertEquals(PartSlot.SUSPENSION, g2.tiles.first { it.name == "Shockwave" }.slot)
    }

    @Test fun driverPairAndGrid() {
        val g = p("team_drivers_top").game!!
        assertEquals(2, g.driverPair.size)
        assertEquals("D. Hill", g.driverPair[0].first)
        assertEquals(65, g.driverPair[0].second.defending)
        assertEquals(47, g.driverPair[0].second.tyreManagement)
        assertEquals(54, g.driverPair[1].second.tyreManagement)
        val tiles = g.tiles.associateBy { it.name }
        assertEquals(413 to 400, tiles.getValue("G.Bortoleto").let { it.cardsOwned to it.cardsNeeded })
        assertTrue(tiles.getValue("G.Bortoleto").featured)
    }

    @Test fun objectives() {
        val g = p("objectives").game!!
        assertEquals(8, g.series)
        assertEquals((16 * 60 + 8) * 60_000L, g.objectivesDailyLeftMs)
        assertEquals((4 * 24 + 16) * 3_600_000L, g.objectivesWeeklyLeftMs)
        val first = g.objectives.first()
        assertFalse(first.weekly)
        assertTrue(first.text, first.text.contains("Duelos"))
        assertEquals(0 to 5, first.progress to first.target)
        val weekly = g.objectives.filter { it.weekly }
        assertTrue(weekly.any { it.text.contains("Czixotes") && it.progress == 8 && it.target == 30 })
        assertTrue(g.objectives.size >= 6)
    }

    @Test fun grandPrix() {
        val menu = p("race_menu").game!!.gp!!
        assertEquals("austin", menu.trackId)
        assertEquals("GP EUA (Austin (COTA))", menu.name)
        assertEquals("Rodada classificatória", menu.round)
        assertEquals((14 * 60 + 55) * 60_000L, menu.timeLeftMs)
        assertEquals(10, menu.featuredBoostPct)

        val detail = p("gp_detail").game!!.gp!!
        assertEquals(700, detail.recommendedScore)
        assertEquals(400_000L, detail.entryFee)

        val board = p("gp_board").game!!.gp!!
        assertEquals(1, board.racesPlayed); assertEquals(4, board.racesTotal)
        assertTrue(board.leaderboard.any { it.first == "LezndroRibeiro" && it.second == 0 })
    }

    @Test fun featuredBoostersClubAndPass() {
        assertTrue(p("store_featured").game!!.featured.containsAll(listOf("Johnny Herbert", "Andrea KimiAntonelli")))
        val boosters = p("boosters").game!!.boosters
        assertEquals(19, boosters["Cogumelo"])
        val club = p("club_league").game!!
        assertEquals("FLAF1", club.clubName)
        assertEquals(25_243L, club.clubRep)
        assertEquals(((13 * 24 + 15) * 60 + 6) * 60_000L + 56_000L, club.clubLeagueLeftMs)
        val pass = p("pit_pass").game!!
        assertEquals("EXIBICAO 71", pass.seasonName)
        assertNotNull(pass.seasonLeftMs)
    }
}
