package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.extension.models.DabPlaylist
import dev.brahmkshatriya.echo.extension.models.DabTrack
import dev.brahmkshatriya.echo.extension.models.DabUser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class Converter {

    var api: DABApi? = null

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    // Precompile regexes used frequently to avoid repeated allocations
    private companion object {
        val LRC_LINE_REGEX = Regex("\\[(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?](.*)")
        val BRACKET_TIMESTAMP_REGEX = Regex("[\\[(]\\s*\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?\\s*[)\\]]")
        val FULL_HMS_REGEX = Regex("\\b\\d{1,2}:\\d{2}:\\d{2}(?:[.:]\\d{1,3})?\\b")
        val MMSS_REGEX = Regex("\\b\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?\\b")
        val LEADING_SEPARATORS_REGEX = Regex("^[\\s-:]+|[\\s-:]+$")
        val MULTI_SPACE_REGEX = Regex("[\\t ]+")
        val MULTI_NEWLINE_REGEX = Regex("(?:\\n\\s*){2,}")
    }

    fun toUser(user: DabUser): User {
        return User(
            id = user.id.toString(),
            name = user.username
        )
    }

    fun toPlaylist(playlist: DabPlaylist): Playlist {
        return Playlist(
            id = playlist.id,
            title = playlist.name,
            authors = listOf(Artist(id = "user", name = "You")),
            isEditable = false,
            trackCount = playlist.trackCount.toLong(),
            // Generate a deterministic placeholder color cover based on playlist id/name
            cover = null
        )
    }



    fun toTrack(track: DabTrack): Track {
        val streamUrl = "https://dab.yeet.su/api/stream?trackId=${track.id}"

        val finalStreamUrl = streamUrl

        track.audioQuality?.let { aq ->

        }

        // Determine a simple numeric quality for sorting and a human-readable file format
        val (quality, qualityDescription) = run {
            val aq = track.audioQuality
            if (aq == null) {
                1 to "Unknown"
            } else {
                val bitDepth = aq.maximumBitDepth
                val sampleRate = aq.maximumSamplingRate
                val isHiRes = aq.isHiRes

                // Simple numeric quality used for sorting only (smaller range)
                val numericQuality = when {
                    isHiRes -> 3
                    bitDepth >= 16 && sampleRate >= 44.1 -> 2
                    else -> 1
                }

                // Map basic characteristics to a likely container/format string
                val format = when {
                    isHiRes -> "FLAC"
                    bitDepth >= 16 && sampleRate >= 44.1 -> "FLAC"
                    else -> "MP3"
                }

                numericQuality to format
            }
        }

        val artist = Artist(
            id = track.artistId?.toString() ?: track.artist,
            name = track.artist,
            // Prefer album cover as a fallback for artist image to avoid API call here
            cover = track.albumCover?.toImageHolder()
        )

        // Create album with cover for info tab
        val album = track.albumTitle?.let {
            Album(
                id = track.albumId ?: it,
                title = it,
                cover = track.albumCover?.toImageHolder(),
                artists = listOf(artist)
            )
        }

        return Track(
            id = track.id.toString(),
            title = track.title,
            cover = track.albumCover?.toImageHolder(),
            artists = listOf(artist),
            album = album,
            // Provide a lightweight placeholder Streamable so the player has an item to
            // request media for. This avoids eagerly resolving CDN URLs while allowing
            // the player to call loadStreamableMedia when it needs to play a track.
            streamables = listOf(
                Streamable.server(
                    id = track.id.toString(),
                    quality = quality,
                    extras = mapOf(
                        "dab_id" to track.id.toString(),
                        "stream_url" to finalStreamUrl,
                        "qualityDescription" to qualityDescription,
                        "bitDepth" to (track.audioQuality?.maximumBitDepth?.toString() ?: "Unknown"),
                        "sampleRate" to (track.audioQuality?.maximumSamplingRate?.toString() ?: "Unknown"),
                        "isHiRes" to (track.audioQuality?.isHiRes?.toString() ?: "false")
                    )
                )
            ),
            duration = track.duration.toLong() * 1000
        )
    }

    // Map an Echo Track to a DAB-compatible JSON object for POSTing to /favorites
    fun toDabTrackJson(track: Track): JsonObject {
        return buildJsonObject {
            // Some DAB servers expect a numeric `id`. Send a numeric id when the track.id is parseable,
            // but always include a string `trackId` alias to be robust across deployments.
            val idNum = track.id.toLongOrNull()
            if (idNum != null) put("id", JsonPrimitive(idNum)) else put("id", JsonPrimitive(track.id))
            put("trackId", JsonPrimitive(track.id))
            put("title", JsonPrimitive(track.title))
            put("name", JsonPrimitive(track.title))

            val firstArtist = track.artists.firstOrNull()
            put("artist", JsonPrimitive(firstArtist?.name ?: ""))
            put("artistName", JsonPrimitive(firstArtist?.name ?: ""))
            firstArtist?.id?.let { put("artistId", JsonPrimitive(it)) }

            track.album?.let { alb ->
                put("albumTitle", JsonPrimitive(alb.title))
                put("albumId", JsonPrimitive(alb.id))
                alb.cover?.let { cover ->
                    try {
                        val urlStr = when (cover) {
                            is dev.brahmkshatriya.echo.common.models.ImageHolder.NetworkRequestImageHolder -> cover.request.url
                            is dev.brahmkshatriya.echo.common.models.ImageHolder.ResourceUriImageHolder -> cover.uri
                            else -> null
                        }
                        if (!urlStr.isNullOrBlank()) put("albumCover", JsonPrimitive(urlStr))
                    } catch (_: Throwable) {}
                }
            }

            track.duration?.let { durMs -> put("duration", JsonPrimitive((durMs / 1000).toInt())) }

            track.extras["releaseDate"]?.let { put("releaseDate", JsonPrimitive(it)) }
            track.extras["genre"]?.let { put("genre", JsonPrimitive(it)) }

            // audioQuality
            val bitDepth = track.extras["bitDepth"]
            val sampleRate = track.extras["sampleRate"]
            val isHiRes = track.extras["isHiRes"]
            if (!bitDepth.isNullOrBlank() || !sampleRate.isNullOrBlank() || !isHiRes.isNullOrBlank()) {
                val aq = buildJsonObject {
                    bitDepth?.toIntOrNull()?.let { put("maximumBitDepth", JsonPrimitive(it)) }
                    sampleRate?.toDoubleOrNull()?.let { put("maximumSamplingRate", JsonPrimitive(it)) }
                    if (!isHiRes.isNullOrBlank()) put("isHiRes", JsonPrimitive(isHiRes.toBooleanStrictOrNull() ?: (isHiRes == "true")))
                }
                put("audioQuality", aq)
            }

            if (track.extras.isNotEmpty()) {
                val extrasObj = buildJsonObject {
                    for ((k, v) in track.extras) put(k, JsonPrimitive(v))
                }
                put("extras", extrasObj)
            }
        }
    }

    fun toAlbum(album: dev.brahmkshatriya.echo.extension.models.DabAlbum): Album {
        return Album(
            id = album.id,
            title = album.title,
            cover = album.cover?.toImageHolder(),
            artists = listOf(Artist(id = album.artistId?.toString() ?: album.artist, name = album.artist)),
            trackCount = album.trackCount.toLong(),
            duration = album.tracks?.sumOf { it.duration * 1000L }, // Convert to milliseconds
            releaseDate = album.releaseDate?.let {
                try {
                    // Try to parse different date formats
                    when {
                        it.contains("-") -> {
                            val parts = it.split("-")
                            if (parts.size >= 3) {
                                val day = parts[2].toIntOrNull()
                                val month = parts[1].toIntOrNull()
                                val year = parts[0].toIntOrNull()
                                if (year != null) {
                                    dev.brahmkshatriya.echo.common.models.Date(
                                        day = day,
                                        month = month,
                                        year = year
                                    )
                                } else null
                            } else if (parts.size >= 2) {
                                val month = parts[1].toIntOrNull()
                                val year = parts[0].toIntOrNull()
                                if (year != null) {
                                    dev.brahmkshatriya.echo.common.models.Date(
                                        month = month,
                                        year = year
                                    )
                                } else null
                            } else {
                                val year = parts[0].toIntOrNull()
                                if (year != null) {
                                    dev.brahmkshatriya.echo.common.models.Date(year = year)
                                } else null
                            }
                        }
                        it.length == 4 -> {
                            val year = it.toIntOrNull()
                            if (year != null) {
                                dev.brahmkshatriya.echo.common.models.Date(year = year)
                            } else null
                        }
                        else -> null
                    }
                } catch (_: Throwable) { null }
            },
            subtitle = album.releaseDate?.let { "Released $it" },
            extras = mapOf(
                "dab_id" to album.id,
                "artist_id" to (album.artistId?.toString() ?: ""),
                "release_date" to (album.releaseDate ?: "")
            ).filterValues { it.isNotBlank() }
        )
    }

    fun toArtist(artist: dev.brahmkshatriya.echo.extension.models.DabArtist): Artist {
        return Artist(
            id = artist.id.toString(),
            name = artist.name,
            cover = artist.picture?.toImageHolder(),
            bio = null, // DAB API doesn't provide bio in basic artist info
            subtitle = null,
            extras = mapOf(
                "dab_id" to artist.id.toString(),
                "picture_url" to (artist.picture ?: "")
            ).filterValues { it.isNotBlank() }
        )
    }

    fun toLyricFromText(text: String?): Lyrics.Lyric? {
        if (text == null) return null
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        try {
            val matches = LRC_LINE_REGEX.findAll(trimmed).toList()
            if (matches.isNotEmpty()) {
                val parsed = matches.map { m ->
                    val min = m.groupValues[1].toLong()
                    val sec = m.groupValues[2].toLong()
                    val msStr = m.groupValues.getOrNull(3) ?: ""
                    val ms = when {
                        msStr.isEmpty() -> 0L
                        msStr.length == 1 -> (msStr.toLong() * 100)
                        msStr.length == 2 -> (msStr.toLong() * 10)
                        else -> msStr.padEnd(3, '0').take(3).toLong()
                    }
                    val start = min * 60_000 + sec * 1000 + ms
                    val textPart = m.groupValues[4].trim()
                    Lyrics.Item(textPart, start, start + 3000)
                }.sortedBy { it.startTime }

                val items = parsed.mapIndexed { i, item ->
                    if (i < parsed.size - 1) item.copy(endTime = parsed[i + 1].startTime) else item
                }
                return Lyrics.Timed(items)
            }
        } catch (_: Throwable) {
            // LRC parsing failed -> continue to other parsers
        }

        // JSON-shaped lyrics parsing
        try {
            val elem = json.parseToJsonElement(trimmed)
            val lyricFromJson = parseJsonElementToLyric(elem)
            if (lyricFromJson != null) return lyricFromJson
        } catch (_: Throwable) {
            // not JSON or parse failed - fallthrough to plain text
        }

        // Fallback: cleaned plain text
        val cleaned = stripLrcTimestamps(trimmed)
        return Lyrics.Simple(cleaned)
    }

    private fun parseJsonElementToLyric(elem: kotlinx.serialization.json.JsonElement): Lyrics.Lyric? {
        when (elem) {
            is JsonArray -> return parseJsonArrayToLyric(elem)
            is JsonObject -> {
                // Try to find arrays inside object
                val arr = when {
                    elem["lyrics"] is JsonArray -> elem["lyrics"] as JsonArray
                    elem["lines"] is JsonArray -> elem["lines"] as JsonArray
                    elem["data"] is JsonArray -> elem["data"] as JsonArray
                    else -> null
                }
                if (arr != null) return parseJsonArrayToLyric(arr)
            }
            else -> return null
        }
        return null
    }

    private fun parseJsonArrayToLyric(arr: JsonArray): Lyrics.Lyric? {
        val timedItems = mutableListOf<Lyrics.Item>()
        val wordGroups = mutableListOf<List<Lyrics.Item>>()

        for (el in arr) {
            when (el) {
                is JsonPrimitive -> {
                    // primitive line without timing - skip
                }
                is JsonObject -> {
                    // detect word-by-word structure
                    val wordsEl = el["words"] ?: el["lineWords"] ?: el["wordTiming"]
                    if (wordsEl is JsonArray) {
                        val group = mutableListOf<Lyrics.Item>()
                        for (w in wordsEl) {
                            if (w is JsonObject) {
                                val text = (w["text"] as? JsonPrimitive)?.content ?: (w["word"] as? JsonPrimitive)?.content ?: ""
                                val timeStr = (w["time"] as? JsonPrimitive)?.content ?: (w["start"] as? JsonPrimitive)?.content ?: (w["t"] as? JsonPrimitive)?.content
                                val start = timeStr?.let { parseTimeToMillis(it) } ?: 0L
                                val end = start + (w["duration"]?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() } ?: 300L)
                                group.add(Lyrics.Item(text, start, end))
                            }
                        }
                        if (group.isNotEmpty()) {
                            wordGroups.add(group)
                        }
                        continue
                    }

                    // detect timed line
                    val timeStr = (el["time"] as? JsonPrimitive)?.content ?: (el["start"] as? JsonPrimitive)?.content ?: (el["timestamp"] as? JsonPrimitive)?.content ?: (el["t"] as? JsonPrimitive)?.content
                    val text = (el["text"] as? JsonPrimitive)?.content ?: (el["line"] as? JsonPrimitive)?.content ?: (el["lyrics"] as? JsonPrimitive)?.content ?: el.values.filterIsInstance<JsonPrimitive>().joinToString(" ") { it.content }
                    if (timeStr != null) {
                        val start = parseTimeToMillis(timeStr) ?: continue
                        val item = Lyrics.Item(text.trim(), start, start + 3000)
                        timedItems.add(item)
                    }
                }
                else -> { /* ignore */ }
            }
        }

        if (wordGroups.isNotEmpty()) return Lyrics.WordByWord(wordGroups)
        if (timedItems.isNotEmpty()) {
            val sorted = timedItems.sortedBy { it.startTime }
            val items = sorted.mapIndexed { i, item ->
                if (i < sorted.size - 1) item.copy(endTime = sorted[i + 1].startTime) else item
            }
            return Lyrics.Timed(items)
        }

        return null
    }

    private fun parseTimeToMillis(time: String): Long? {
        val trimmed = time.trim()
        // numeric seconds
        val asDouble = trimmed.toDoubleOrNull()
        if (asDouble != null) return (asDouble * 1000).toLong()

        val parts = trimmed.split(':')
        try {
            if (parts.size == 3) {
                val h = parts[0].toLong()
                val m = parts[1].toLong()
                val sParts = parts[2].split('.', ',')
                val sec = sParts[0].toLong()
                val ms = sParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
                return h * 3600_000 + m * 60_000 + sec * 1000 + ms
            }
            if (parts.size == 2) {
                val m = parts[0].toLong()
                val sParts = parts[1].split('.', ',')
                val sec = sParts[0].toLong()
                val ms = sParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
                return m * 60_000 + sec * 1000 + ms
            }
        } catch (_: Exception) {}
        return null
    }

    private fun stripLrcTimestamps(input: String): String {
        if (input.isBlank()) return ""

        // Remove simple HTML tags first
        var s = input.replace(Regex("<[^>]+>"), " ")

        s = s.replace(BRACKET_TIMESTAMP_REGEX, " ")

        s = s.replace(FULL_HMS_REGEX, " ")

        s = s.replace(MMSS_REGEX, " ")

        s = s.lines().joinToString("\n") { line ->
            line.replace(LEADING_SEPARATORS_REGEX, "").trim()
        }
        s = s.replace(MULTI_SPACE_REGEX, " ")
        s = s.lines().joinToString("\n") { it.trim() }
        s = s.replace(MULTI_NEWLINE_REGEX, "\n")
        return s.trim()
    }

    fun cleanPlainText(text: String?): String {
        if (text == null) return ""
        return stripLrcTimestamps(text)
    }
}