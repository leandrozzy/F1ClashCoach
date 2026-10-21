package com.leandro.f1clashcoach.core

import com.leandro.f1clashcoach.core.engine.ClubManager
import com.leandro.f1clashcoach.core.engine.ClubMemberRead
import com.leandro.f1clashcoach.core.engine.ClubState
import com.leandro.f1clashcoach.core.engine.DuelOddsCalc
import com.leandro.f1clashcoach.core.engine.StoreWatch
import com.leandro.f1clashcoach.core.engine.TrackPlaybook
import com.leandro.f1clashcoach.core.model.RaceRecord
import com.leandro.f1clashcoach.core.model.SeriesCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClubTest {
    private val day = 86_400_000L

    @Test fun readsMembersFromRealScreen() {
        val g = RealFramesTest.p("club_members").game!!
        val names = g.clubMembers.map { it.name }
        assertTrue(names.toString(), g.clubMembers.size == 11)
        val w = g.clubMembers.first { it.name.startsWith("Wall") }
        assertEquals(3055L, w.rep)
        assertEquals("Membro", w.role)
        assertEquals("Presidente", g.clubMembers.first { it.name.startsWith("Lezndro") }.role)
        assertEquals("Vice-Capitão", g.clubMembers.first { it.name.startsWith("Cachorro") }.role)
        assertEquals("Capitão", g.clubMembers.first { it.name.startsWith("Gust") }.role)
        assertEquals(100L, g.clubMembers.first { it.name.startsWith("Artur") }.rep)
        assertEquals(50, g.clubMemberCount)
        // a aba Liga lista clubes, não membros
        assertTrue(RealFramesTest.p("club_league").game!!.clubMembers.isEmpty())
    }

    @Test fun freeCrateTimer() {
        val g = RealFramesTest.p("store_crates").game!!
        assertEquals((3 * 60 + 56) * 60_000L + 33_000L, g.freeCrateLeftMs)
    }

    @Test fun parsesRules() {
        val r = ClubManager.parseRules("Regras: mínimo 300 de reputação por semana. 3 dias sem correr = expulsão!")
        assertEquals(300, r.minRepWeek)
        assertEquals(3, r.maxInactiveDays)
        val r2 = ClubManager.parseRules("Inativos por 5 dias serão removidos. Meta 1.000 rep semanal")
        assertEquals(5, r2.maxInactiveDays)
        assertEquals(1000, r2.minRepWeek)
    }

    @Test fun flagsInactiveAndSlowMembers() {
        val t0 = 1_000_000_000_000L
        var s = ClubState(memberCount = 50, rules = ClubManager.parseRules("mínimo 300 de reputação por semana, 3 dias sem correr = expulsão"))
        s = ClubManager.merge(s, listOf(
            ClubMemberRead("Ativo", "Membro", 1000), ClubMemberRead("Parado", "Membro", 500),
            ClubMemberRead("Lento", "Membro", 400), ClubMemberRead("Chefe", "Presidente", 10), ClubMemberRead("Vice", "Vice-Capitão", 20),
        ), t0)
        s = ClubManager.merge(s, listOf(
            ClubMemberRead("Ativo", "Membro", 1400), ClubMemberRead("Parado", "Membro", 500),
            ClubMemberRead("Lento", "Membro", 450), ClubMemberRead("Chefe", "Presidente", 10), ClubMemberRead("Vice", "Vice-Capitão", 20),
        ), t0 + 4 * day)
        val rep = ClubManager.evaluate(s, "Chefe", t0 + 4 * day)
        val byName = rep.kicks.associateBy { it.name }
        assertEquals(2, byName.getValue("Parado").urgency)
        assertTrue(byName.containsKey("Lento"))
        assertTrue(!byName.containsKey("Ativo") && !byName.containsKey("Chefe"))
        assertEquals(0, byName.getValue("Vice").urgency)
        assertTrue(rep.whenToAct.startsWith("Agora"))
    }

    @Test fun mergesOcrVariantsOfSameName() {
        var s = ClubManager.merge(ClubState(), listOf(ClubMemberRead("ViivianeCarvalho", "Membro", 100)), 0)
        s = ClubManager.merge(s, listOf(ClubMemberRead("VivianeCarvalho", "Membro", 120)), day)
        assertEquals(1, s.members.size)
    }

    @Test fun duelOddsAndPlaybook() {
        val s12 = SeriesCatalog.defaults.last()
        val fav = DuelOddsCalc.estimate(1500, 1380, emptyList(), s12)
        assertEquals("Favorito", fav.verdict)
        assertTrue(!fav.confident && fav.expectedFlags == null)
        assertTrue(fav.headline.contains("sem histórico"))
        val hist = (1..10).map { RaceRecord("h$it", it.toLong(), "monza", won = it <= 7, myScore = 1500, rivalScore = 1390) }
        val real = DuelOddsCalc.estimate(1500, 1380, hist, s12)
        assertTrue(real.confident); assertEquals(70, real.winPct); assertNotNull(real.expectedFlags)
        assertEquals("Muito difícil", DuelOddsCalc.estimate(1300, 1450, emptyList(), s12).verdict)
        val races = (1..4).map { RaceRecord("$it", it.toLong(), "monza", won = it != 4, strategy = "M→H") } +
            (5..6).map { RaceRecord("$it", it.toLong(), "monza", won = false, strategy = "S→M") }
        val play = TrackPlaybook.forTrack(races, "monza")!!
        assertEquals("M→H", play.bestStrategy)
        assertEquals(listOf("Max Verstappen"), StoreWatch.matches(listOf("Max Verstappen", "Hex"), listOf("M. Verstappen")))
    }
}
