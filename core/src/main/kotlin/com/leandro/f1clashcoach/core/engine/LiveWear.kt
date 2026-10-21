package com.leandro.f1clashcoach.core.engine

import com.leandro.f1clashcoach.core.model.Tyre
import kotlin.math.floor

/** Plano de pit de um piloto, calculado com o desgaste medido nesta corrida. */
data class PitCall(
    val driver: Int,
    val tyre: Tyre,
    val life: Int,
    /** % de pneu por volta (medido; ou pela faixa do jogo antes da 1ª volta completa). */
    val wearPerLap: Double,
    val measured: Boolean,
    /** Volta em cujo fim deve parar (null = não precisa parar). */
    val pitAtEndOfLap: Int?,
    /** Pneu para depois da parada. */
    val nextTyre: Tyre?,
    val lifeAtFinish: Int?,
    val text: String,
    val urgent: Boolean,
)

/**
 * Mede o desgaste real de cada pneu durante a corrida (vida lida no painel a cada volta) e diz
 * se o pneu chega ao fim ou em que volta parar — sem chute: usa só o que a corrida mostra e as
 * faixas de voltas que o próprio jogo informa na escolha de pneus.
 */
class LiveWear {
    private data class Obs(val lap: Int, val life: Int)
    private data class Stint(val tyre: Tyre, val obs: MutableList<Obs>)

    private val stints = HashMap<Int, Stint>()

    fun reset() = stints.clear()

    /** Registra o painel de um piloto. Nova parada (pneu trocado ou vida subiu) começa outro stint. */
    fun observe(driver: Int, lap: Int?, tyre: Tyre?, life: Int?) {
        if (lap == null || tyre == null || life == null) return
        val s = stints[driver]
        if (s == null || s.tyre != tyre || life > (s.obs.lastOrNull()?.life ?: 0) + 10) {
            stints[driver] = Stint(tyre, mutableListOf(Obs(lap, life)))
            return
        }
        val last = s.obs.last()
        if (lap > last.lap || life < last.life) s.obs += Obs(lap, life)
    }

    /** % por volta medido: queda de vida entre a primeira e a última volta vistas do stint. */
    fun measuredRate(driver: Int): Double? {
        val o = stints[driver]?.obs ?: return null
        val first = o.first(); val last = o.last()
        val laps = last.lap - first.lap
        if (laps < 1) return null
        return ((first.life - last.life).toDouble() / laps).takeIf { it > 0 }
    }

    /**
     * Decide a parada. [ranges] = faixas de voltas por composto mostradas pelo jogo ("VOLTAS 2-3"),
     * [lapTimes] = tempo por volta de cada composto (tela de pneus), para escolher o pneu seguinte.
     */
    fun call(
        driver: Int, lap: Int?, totalLaps: Int?, tyre: Tyre?, life: Int?,
        ranges: Map<Tyre, IntRange>, lapTimes: Map<Tyre, Double>, wet: Boolean = false,
    ): PitCall? {
        if (lap == null || totalLaps == null || tyre == null || life == null) return null
        val remaining = totalLaps - lap + 1
        val measured = measuredRate(driver)
        val fromGame = ranges[tyre]?.let { r -> 100.0 / ((r.first + r.last) / 2.0).coerceAtLeast(0.5) }
        val rate = measured ?: fromGame ?: return null
        // o jogo avisa pelo rádio por volta de 10–15%: abaixo disso o carro fica muito lento
        val usable = (life - 10).coerceAtLeast(0)
        val lapsLeft = usable / rate
        val name = "P${driver + 1}"
        if (lapsLeft >= remaining) {
            val atEnd = (life - rate * remaining).toInt().coerceAtLeast(0)
            return PitCall(driver, tyre, life, rate, measured != null, null, null, atEnd,
                "$name: sem parada — o ${tyre.short} chega ao fim com ~$atEnd%" + if (measured == null) " (pela faixa do jogo)" else "", false)
        }
        val pitLap = (lap + floor(lapsLeft).toInt() - 1).coerceAtLeast(lap)
        val after = totalLaps - pitLap
        // pneu seguinte: o mais rápido cuja faixa (do jogo) cobre as voltas que faltam
        val dry = listOf(Tyre.SOFT, Tyre.MEDIUM, Tyre.HARD)
        val options = (if (wet) listOf(Tyre.INTER, Tyre.WET) else dry).filter { t -> ranges[t]?.let { it.last >= after } ?: false }
        val next = options.minByOrNull { lapTimes[it] ?: 99.0 }
            ?: (if (wet) Tyre.INTER else dry.filter { ranges.containsKey(it) }.maxByOrNull { ranges[it]!!.last } ?: Tyre.HARD)
        val urgent = pitLap <= lap
        return PitCall(driver, tyre, life, rate, measured != null, pitLap, next, null,
            (if (urgent) "$name: BOX nesta volta" else "$name: pare no fim da volta $pitLap") + " → ${next.short} (faltarão $after voltas)" +
                " • gasto ${"%.0f".format(rate)}%/volta" + if (measured == null) " pela faixa do jogo" else " medido",
            urgent)
    }
}

/** Plano de pneus montado só com o que a tela de pneus do jogo mostra (faixas de voltas e tempo por volta). */
data class RangePlan(val stints: List<Pair<Tyre, Int>>, val totalTime: Double) {
    val stops: Int get() = stints.size - 1
    fun summary(): String = stints.joinToString(" → ") { "${it.first.short} ${it.second}v" } +
        if (stops == 0) " (sem parada)" else " ($stops parada${if (stops > 1) "s" else ""})"
}

object RangePlanner {
    /**
     * Combina compostos de modo que cada trecho caiba na faixa de voltas do jogo e a soma dê [laps].
     * Custo = voltas × tempo por volta de cada composto + [pitLoss] por parada.
     */
    fun plans(laps: Int, ranges: Map<Tyre, IntRange>, lapTimes: Map<Tyre, Double>, pitLoss: Double, top: Int = 3): List<RangePlan> {
        val dry = listOf(Tyre.SOFT, Tyre.MEDIUM, Tyre.HARD).filter { ranges.containsKey(it) && lapTimes.containsKey(it) }
        val out = ArrayList<RangePlan>()
        fun rec(left: Int, acc: List<Pair<Tyre, Int>>) {
            if (acc.size > 3) return
            if (left == 0) {
                val time = acc.sumOf { (t, n) -> n * lapTimes.getValue(t) } + (acc.size - 1) * pitLoss
                out += RangePlan(acc, time); return
            }
            for (t in dry) {
                val r = ranges.getValue(t)
                for (n in 1..minOf(r.last, left)) rec(left - n, acc + (t to n))
            }
        }
        rec(laps, emptyList())
        return out.sortedBy { it.totalTime }.distinctBy { it.summary() }.take(top)
    }
}
