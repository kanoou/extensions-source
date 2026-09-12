package eu.kanade.tachiyomi.extension.es.eternalmangas

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element

@Source
abstract class EternalMangas : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get(baseUrl).asJsoup()
        val mangas = document.select("section[aria-labelledby=home-trending-heading] div.em-reveal-group > a").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                title = element.selectFirst("div.small")!!.text()
                thumbnail_url = element.selectFirst("img")!!.attr("src")
            }
        }
        return MangasPage(mangas, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get("$baseUrl/?page=$page").asJsoup()
        val mangas = document.select("section[aria-labelledby=home-updates-heading] div.em-reveal-group > div").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
                title = element.selectFirst("div.small")!!.text()
                thumbnail_url = element.selectFirst("img")!!.attr("src")
            }
        }
        val hasNextPage = document.selectFirst("ul.pagination li:last-child:not(.disabled)") != null
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("page", page.toString())
            .build()

        val document = client.get(url).asJsoup()
        val mangas = document.select("div.em-reveal-group > div").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
                title = element.selectFirst("div.em-series-catalog-title")!!.text()
                thumbnail_url = element.selectFirst("img")!!.attr("src")
            }
        }
        val hasNextPage = document.selectFirst("ul.pagination li:last-child:not(.disabled)") != null
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url, headers).asJsoup()

        val updatedManga = SManga.create().apply {
            thumbnail_url = document.selectFirst("img.em-cover-lg")?.attr("abs:src")
            status = parseStatus(document.selectFirst("li:has(strong:contains(Estado))")?.ownText())
            title = document.selectFirst("h1.em-reveal")!!.text()
            description = document.selectFirst("#resume-content")?.text()
            genre = document.select("#tab-sinopsis div.em-reveal > spam.em-badge").joinToString { it.text().trim() }
        }

        val chapterList = document.select("#chapter-list > .chapter-item").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
                name = element.selectFirst("div.text-wrap")!!.text()
            }
        }

        return SMangaUpdate(
            manga = updatedManga,
            chapters = chapterList,
        )
    }

    private fun parseStatus(status: String?): Int = when (status?.lowercase()) {
        "en emisión" -> SManga.ONGOING
        "finalizado" -> SManga.COMPLETED
        "abandonado", "cancelado" -> SManga.CANCELLED
        "pausado" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(baseUrl + chapter.url).asJsoup()
        return document.select("#em-reader-pages img").mapIndexed { index, element ->
            Page(index, imageUrl = element.imgAttr())
        }
    }

    private fun Element.imgAttr(): String? = when {
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("src") -> attr("abs:src")
        else -> null
    }
}
