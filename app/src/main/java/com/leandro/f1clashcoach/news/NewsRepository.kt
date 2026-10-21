package com.leandro.f1clashcoach.news

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

data class NewsItem(val source: String, val title: String, val url: String)

/** Busca notícias, patch notes e guias públicos do F1 Clash. */
object NewsRepository {

    private data class Source(val name: String, val url: String, val mustContain: List<String>)

    private val sources = listOf(
        Source("Hutch • Patch notes", "https://www.hutch.io/our-games/f1-clash/patch-notes/", listOf("f1-clash", "patch")),
        Source("Hutch • Blog", "https://www.hutch.io/blog/", listOf("f1-clash")),
        Source("F1 Clash oficial", "https://f1clashgame.com/news/", listOf("/news/")),
        Source("AllClash • Guias", "https://www.allclash.com/category/f1-clash/", listOf("f1-clash", "series", "event")),
    )

    suspend fun fetch(): Pair<List<NewsItem>, List<String>> = withContext(Dispatchers.IO) {
        coroutineScope {
            val results = sources.map { s -> async { runCatching { load(s) }.fold({ it to null }, { emptyList<NewsItem>() to "${s.name}: ${it.message}" }) } }.awaitAll()
            results.flatMap { it.first } to results.mapNotNull { it.second }
        }
    }

    private fun load(s: Source): List<NewsItem> {
        val doc = Jsoup.connect(s.url)
            .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36")
            .timeout(15_000)
            .get()
        return doc.select("a[href]")
            .asSequence()
            .map { a -> a.text().trim() to a.absUrl("href") }
            .filter { (title, url) -> title.length in 18..140 && url.startsWith("http") && s.mustContain.any { url.contains(it, ignoreCase = true) } }
            .distinctBy { it.second }
            .take(15)
            .map { (title, url) -> NewsItem(s.name, title, url) }
            .toList()
    }
}
