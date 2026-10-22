package com.leandro.f1clashcoach.core

import com.leandro.f1clashcoach.core.engine.GameSync
import com.leandro.f1clashcoach.core.model.Garage
import org.junit.Assert.assertEquals
import org.junit.Test

/** Vídeo do app 1.0.37: tocar nas configurações 2 (940) e 3 (267) do jogo trocava a pontuação da conta. */
class TeamConfigTest {
    private fun read(k: String) = RealFramesTest.p(k).game!!

    @Test fun readsEachConfigScore() {
        assertEquals(1071, read("team_cfg_1071").teamScore)
        assertEquals(940, read("team_cfg_940").teamScore)
        assertEquals(267, read("team_cfg_267").teamScore)
    }

    @Test fun otherConfigsDoNotReplaceMain() {
        var g = GameSync.apply(Garage(), read("team_cfg_1071"), emptyList(), null, 1L).garage
        assertEquals(1071, g.teamScore)
        val eq = g.drivers.filter { it.equipped }.map { it.name }.toSet()
        g = GameSync.apply(g, read("team_cfg_267"), emptyList(), null, 2L).garage
        g = GameSync.apply(g, read("team_cfg_940"), emptyList(), null, 3L).garage
        assertEquals(1071, g.teamScore)
        assertEquals(eq, g.drivers.filter { it.equipped }.map { it.name }.toSet())
    }

    @Test fun wrongSavedScoreHealsWithMain() {
        // conta salva pela versão anterior com 267: a principal (1071) volta ao ser vista
        val g = GameSync.apply(Garage(teamScore = 267), read("team_cfg_1071"), emptyList(), null, 1L).garage
        assertEquals(1071, g.teamScore)
    }
}
