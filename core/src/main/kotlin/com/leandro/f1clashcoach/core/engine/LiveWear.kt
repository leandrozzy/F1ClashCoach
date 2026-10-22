package com.leandro.f1clashcoach.core.engine

import com.leandro.f1clashcoach.core.model.Tyre

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
    /** Vida do pneu no começo de cada volta (primeira leitura da volta) e a última vista. */
    private class Stint(val tyre: Tyre) {
        val lapStart = sortedMapOf<Int, Int>()
        var lastLap = 0
        var lastLife = 100
    }

    private val stints = HashMap<Int, Stint>()

    fun reset() = stints.clear()

    /** Registra o painel de um piloto. Pneu trocado (ou vida que sobe) começa outro stint. */
    fun observe(driver: Int, lap: Int?, tyre: Tyre?, life: Int?) {
        if (lap == null || tyre == null || life == null) return
        var s = stints[driver]
        if (s == null || s.tyre != tyre || life > s.lastLife + 10) {
            s = Stint(tyre); stints[driver] = s
        } else {
            // volta lida errada (voltou para trás ou pulou longe): ignora a leitura
            if (lap < s.lastLap || lap > s.lastLap + 2) return
        }
        if (lap !in s.lapStart) s.lapStart[lap] = life
        s.lastLap = lap; s.lastLife = life
    }

    /** % por volta medido entre começos de volta (nunca no meio de uma volta, que dava 40% falso). */
    fun measuredRate(driver: Int): Double? {
        val st = stints[driver]?.lapStart ?: return null
        if (st.size < 2) return null
        val first = st.entries.first(); val last = st.entries.last()
        val laps = last.key - first.key
        if (laps < 1) return null
        return ((first.value - last.value).toDouble() / laps).takeIf { it > 0 }
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
        val measured = measuredRate(driver)
        val fromGame = ranges[tyre]?.let { r -> 100.0 / ((r.first + r.last) / 2.0).coerceAtLeast(0.5) }
        val rate = measured ?: fromGame ?: return null
        val name = "P${driver + 1}"
        val tag = if (measured == null) " (pela faixa do jogo)" else ""
        // vida no fim DESTA volta: começo da volta menos o gasto de uma volta
        val start = stints[driver]?.takeIf { it.tyre == tyre }?.lapStart?.get(lap) ?: life
        val endOfLap = minOf(life.toDouble(), start - rate)
        val after = totalLaps - lap
        val atFinish = endOfLap - rate * after
        // Parar custa ~20 s: na última volta nunca; se o pneu só acaba nos metros finais, segue.
        fun noStop(): PitCall {
            val f = atFinish.toInt().coerceAtLeast(0)
            return PitCall(driver, tyre, life, rate, measured != null, null, null, f,
                "$name: sem parada — o ${tyre.short} chega ao fim" + (if (f > 0) " com ~$f%" else " no limite") + tag, false)
        }
        if (after <= 0 || atFinish >= -rate * 0.35) return noStop()
        // o jogo avisa pelo rádio por volta de 10%: abaixo disso o carro fica muito lento
        val extra = kotlin.math.floor(((endOfLap - 10) / rate).coerceAtLeast(0.0)).toInt()
        val pitLap = lap + extra
        if (pitLap >= totalLaps) return noStop()
        val left = totalLaps - pitLap
        // pneu seguinte: o mais rápido cuja faixa (do jogo) cobre as voltas que faltam
        val dry = listOf(Tyre.SOFT, Tyre.MEDIUM, Tyre.HARD)
        val options = (if (wet) listOf(Tyre.INTER, Tyre.WET) else dry).filter { t -> ranges[t]?.let { it.last >= left } ?: false }
        val next = options.minByOrNull { lapTimes[it] ?: 99.0 }
            ?: (if (wet) Tyre.INTER else dry.filter { ranges.containsKey(it) }.maxByOrNull { ranges[it]!!.last } ?: Tyre.HARD)
        val urgent = pitLap == lap
        return PitCall(driver, tyre, life, rate, measured != null, pitLap, next, null,
            (if (urgent) "$name: BOX no fim desta volta" else "$name: pare no fim da volta $pitLap") + " → ${next.short} ($left voltas)" +
                " • ~${"%.0f".format(rate)}%/volta" + tag,
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
