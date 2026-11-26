package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings

/**
 * SimpMusic lyrics API. Cloudflare answered 403 to every request from the audit network, so
 * this provider is BLOCKED. Endpoints and field names follow the reference client.
 */
internal class SimpMusicSource : LyricsSource {

    private val throttle = Throttle(500)
    private val headers = mapOf(
        "User-Agent" to "SimpMusicLyrics/1.0",
        "Accept" to "application/json",
    )

    override suspend fun search(track: Track, settings: Settings?): List<Lyrics> {
        val query = track.query()
        if (query.title.isBlank()) return emptyList()
        throttle.await()
        val term = Http.encode(listOf(track.title, query.artist).joinToString(" "))
        val body = runCatching { Http.get(BASE + "search?q=$term&limit=10", headers) }.getOrNull()
            ?: return emptyList()
        val data = runCatching { Http.json(body).obj()?.obj("data")?.arr("searchResult").objects() }
            .getOrNull() ?: runCatching { Http.json(body).obj()?.arr("data").objects() }.getOrNull()
            ?: return emptyList()

        return data.mapNotNull { entry ->
            val videoId = entry.str("videoId") ?: return@mapNotNull null
            val title = entry.str("songTitle") ?: entry.str("title") ?: return@mapNotNull null
            val artist = entry.str("artistName") ?: entry.str("artist").orEmpty()
            val duration = (entry.long("duration") ?: 0L) / 1000
            Triple(videoId, Candidate(title, artist, "", duration), entry)
        }.filter { Match.accept(query, it.second) }
            .sortedByDescending { Match.score(query, it.second) }
            .take(6)
            .map { (videoId, candidate, _) ->
                Lyrics(
                    id = "simpmusic:$videoId",
                    title = candidate.title,
                    subtitle = candidate.artist + " • SimpMusic",
                    lyrics = null,
                    extras = mapOf("videoId" to videoId),
                )
            }
    }

    override suspend fun load(lyrics: Lyrics, settings: Settings?): Lyrics {
        val videoId = lyrics.extras["videoId"] ?: throw LyricsException("SimpMusic candidate is missing its video id")
        throttle.await()
        val body = Http.get(BASE + Http.encode(videoId), headers)
        val root = Http.json(body).obj() ?: throw LyricsException("Empty SimpMusic response")
        if (root.bool("success") == false) throw LyricsException("SimpMusic has no lyrics for this video")
        val entries = root.arr("data").objects()
        if (entries.isEmpty()) throw LyricsException("SimpMusic has no lyrics for this video")

        val best = entries.maxByOrNull { it.long("vote") ?: 0L }
            ?: throw LyricsException("SimpMusic returned no entries")
        val synced = best.str("syncedLyrics")
        if (!synced.isNullOrBlank()) {
            val lines = Lrc.parse(synced)
            if (lines.isNotEmpty()) return lyrics.copy(lyrics = timed(lines, lines.any { it.words.size > 1 }))
        }
        val plainText = best.str("plainLyric") ?: best.str("plainLyrics")
        if (!plainText.isNullOrBlank()) {
            val lines = Lrc.parsePlain(plainText)
            if (lines.isNotEmpty()) return lyrics.copy(lyrics = plain(lines.joinToString("\n")))
        }
        throw LyricsException("SimpMusic returned no usable lyric format")
    }

    companion object {
        private const val BASE = "https://api-lyrics.simpmusic.org/v1/"

        fun settings(): List<Setting> = emptyList()
    }
}
