package com.leandro.f1clashcoach.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.leandro.f1clashcoach.core.engine.GridTile
import com.leandro.f1clashcoach.core.engine.Txt
import com.leandro.f1clashcoach.core.model.Catalog
import com.leandro.f1clashcoach.core.model.CollectionItem
import com.leandro.f1clashcoach.core.model.Rarity
import java.io.File

/**
 * Imagens das cartas recortadas das SUAS telas do jogo (Equipe › Pilotos/Carros), guardadas só no aparelho.
 * Assim a garagem mostra a foto real de cada piloto e peça, como no jogo.
 */
object ArtStore {
    private val cache = object : LruCache<String, Bitmap>(80) {}
    @Volatile var version = 0
        private set

    // "art2": recorte novo (sem selos); as fotos antigas são ignoradas e refeitas na próxima leitura
    private fun dir(ctx: Context) = File(ctx.filesDir, "art2").apply { mkdirs() }

    private fun clean(s: String) = Txt.compact(s).lowercase()

    fun driverKey(name: String, rarity: Rarity, edition: String = ""): String {
        val canon = Catalog.findDrivers(name).firstOrNull()?.name ?: name
        return "d_" + clean(canon) + "_" + rarity.name.lowercase() + if (edition.isNotBlank()) "_" + clean(edition) else ""
    }

    fun partKey(name: String): String = "p_" + clean(Catalog.findPart(name)?.name ?: name)

    fun keyFor(item: CollectionItem): String =
        if (item.isDriver) driverKey(item.gameName ?: item.name, item.rarity, item.edition) else partKey(item.name)

    fun has(ctx: Context, key: String): Boolean = File(dir(ctx), "$key.jpg").exists()

    fun load(ctx: Context, key: String): Bitmap? {
        cache.get(key)?.let { return it }
        val f = File(dir(ctx), "$key.jpg")
        if (!f.exists()) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()?.also { cache.put(key, it) }
    }

    /** Recorta a arte das cartas da grade (área acima do nome). Atualiza no máx. 1x por dia por carta. */
    fun captureTiles(ctx: Context, frame: Bitmap, tiles: List<GridTile>, isPart: Boolean) {
        val w = frame.width; val h = frame.height
        var saved = 0
        for (t in tiles) {
            if (t.nameLeft <= 0f || t.nameTop <= 0.1f) continue
            val key = if (isPart) partKey(t.name) else {
                val single = Catalog.findDrivers(t.name).size == 1
                if (t.rarity == Rarity.UNKNOWN && !single) continue
                driverKey(t.name, if (single && t.rarity == Rarity.UNKNOWN) Catalog.findDrivers(t.name)[0].rarity else t.rarity, t.edition ?: "")
            }
            val f = File(dir(ctx), "$key.jpg")
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < 86_400_000L) continue
            // só o miolo da foto: sem a setinha do canto e sem o selo "+10%" (o app desenha os dele)
            val x0 = ((t.nameLeft + 0.04f) * w).toInt(); val y0 = ((t.nameTop - 0.088f) * h).toInt()
            val cw = (0.145f * w).toInt(); val ch = (0.066f * h).toInt()
            if (x0 < 0 || y0 < 0 || x0 + cw > w || y0 + ch > h || cw < 20 || ch < 20) continue
            runCatching {
                val crop = Bitmap.createBitmap(frame, x0, y0, cw, ch)
                val scaled = Bitmap.createScaledBitmap(crop, 180, (180f * ch / cw).toInt().coerceAtLeast(40), true)
                f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 86, it) }
                cache.remove(key)
                saved++
            }
        }
        if (saved > 0) version++
    }
}
