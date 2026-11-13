package com.keyspaceapps.goodtv.data.xtream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParallelMappingTest {
    private val n = 50_000

    private fun names(file: String): List<String> = ConversionEquivalence.corpus(file)

    private fun nullEvery(i: Int, k: Int) = i % k == 0

    private val live: List<XtLiveStream> by lazy {
        val names = names("live_names.txt")
        List(n) { i ->
            XtLiveStream(
                stream_id = if (nullEvery(i, 97)) null else "${i + 1}",
                name = if (nullEvery(i, 53)) null else names[i % names.size],
                stream_icon = if (nullEvery(i, 7)) null else "http://i/$i.png",
                category_id = if (nullEvery(i, 11)) null else "${i % 40}",
                epg_channel_id = if (i % 5 == 0) " " else if (i % 3 == 0) "epg$i" else null,
                num = if (nullEvery(i, 13)) null else i,
            )
        }
    }

    private val movies: List<XtVodStream> by lazy {
        val names = names("movie_names.txt")
        val tmdb = names("tmdb.txt")
        List(n) { i ->
            XtVodStream(
                stream_id = if (nullEvery(i, 89)) null else "${i + 1}",
                name = if (nullEvery(i, 59)) null else names[i % names.size],
                stream_icon = if (nullEvery(i, 7)) null else "http://i/$i.png",
                category_id = if (nullEvery(i, 11)) null else "${i % 40}",
                container_extension = if (nullEvery(i, 5)) null else "mkv",
                rating = if (nullEvery(i, 3)) null else (i % 10).toDouble(),
                added = if (nullEvery(i, 17)) null else i.toLong(),
                tmdb = if (nullEvery(i, 4)) null else tmdb[i % tmdb.size],
            )
        }
    }

    private val series: List<XtSeries> by lazy {
        val names = names("series_names.txt")
        val genres = names("genres.txt")
        val tmdb = names("tmdb.txt")
        List(n) { i ->
            XtSeries(
                series_id = if (nullEvery(i, 83)) null else "${i + 1}",
                name = if (nullEvery(i, 61)) null else names[i % names.size],
                cover = if (nullEvery(i, 7)) null else "http://c/$i.png",
                plot = if (nullEvery(i, 9)) null else "plot $i",
                genre = if (nullEvery(i, 6)) null else genres[i % genres.size],
                rating = if (nullEvery(i, 3)) null else (i % 10).toDouble(),
                category_id = if (nullEvery(i, 11)) null else "${i % 40}",
                tmdb = if (nullEvery(i, 4)) null else tmdb[i % tmdb.size],
            )
        }
    }

    @Test
    fun parallelMappingGivesTheSameListAsSequential() = runBlocking(Dispatchers.Default) {
        val liveSeq = XtreamMappers.live(1L, live)
        val moviesSeq = XtreamMappers.movies(1L, movies)
        val seriesSeq = XtreamMappers.series(1L, series)
        assertTrue("some ids must be dropped", liveSeq.size < n && moviesSeq.size < n && seriesSeq.size < n)

        assertEquals(liveSeq, XtreamMappers.liveParallel(1L, live))
        assertEquals(moviesSeq, XtreamMappers.moviesParallel(1L, movies))
        assertEquals(seriesSeq, XtreamMappers.seriesParallel(1L, series))
    }

    @Test
    fun chunkingKeepsOrderAndDropsAcrossChunkSizes() = runBlocking(Dispatchers.Default) {
        val expected = XtreamMappers.movies(1L, movies)
        for (chunks in listOf(1, 2, 3, 4, 7, 64)) {
            for (min in listOf(1, 2_000, 100_000)) {
                val got = movies.mapInChunks(chunks, min) { XtreamMappers.movies(1L, it) }
                assertEquals("chunks=$chunks min=$min", expected, got)
            }
        }
    }

    @Test
    fun smallAndEmptyListsAreMappedAsOneChunk() = runBlocking(Dispatchers.Default) {
        assertEquals(emptyList<Int>(), emptyList<Int>().mapInChunks(4, 2_000) { it })
        val small = List(1_999) { it }
        assertEquals(small, small.mapInChunks(4, 2_000) { it })
    }
}
