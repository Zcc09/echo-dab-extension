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

class Converter {
    // Allow setting the API instance after construction to avoid circular dependency
    var api: DABApi? = null

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

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
            trackCount = playlist.trackCount.toLong()
        )
    }

    fun toTrack(track: DabTrack): Track {
        val streamUrl = "https://dab.yeet.su/api/stream?trackId=${track.id}"

        track.audioQuality?.let { aq ->

        }

        // Map audio quality to numeric levels and create descriptive text
        val (quality, qualityDescription) = when {
            track.audioQuality != null -> {
                val bitDepth = track.audioQuality.maximumBitDepth
                val sampleRate = track.audioQuality.maximumSamplingRate // This is in kHz, not Hz
                val isHiRes = track.audioQuality.isHiRes

                val numericQuality = when {
                    isHiRes && bitDepth >= 24 && sampleRate >= 192.0 -> 5 // 24bit/192kHz+ Hi-Res
                    isHiRes && bitDepth >= 24 && sampleRate >= 96.0 -> 4  // 24bit/96kHz+ Hi-Res
                    isHiRes && bitDepth >= 24 && sampleRate >= 48.0 -> 3  // 24bit/48kHz+ Hi-Res
                    bitDepth >= 16 && sampleRate >= 44.1 -> 2             // CD Quality 16bit/44.1kHz+
                    else -> 1                                             // Standard quality
                }

                val description = if (isHiRes) {
                    "${bitDepth}bit/${sampleRate.toInt()}kHz Hi-Res"
                } else {
                    "${bitDepth}bit/${sampleRate.toInt()}kHz"
                }

                numericQuality to description
            }
            else -> {
                1 to "Standard Quality"
            }
        }

        // Create artist with image for info tab
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
                // Add album cover from track data
                cover = track.albumCover?.toImageHolder(),
                artists = listOf(artist)
            )
        }

        return Track(
            id = track.id.toString(),
            title = track.title,
            // Add cover to track for the main player display
            cover = track.albumCover?.toImageHolder(),
            artists = listOf(artist),
            album = album,
            streamables = listOf(
                Streamable.server(
                    id = qualityDescription.replace(" ", "_").lowercase(),
                    quality = quality,
                    extras = mapOf(
                        "url" to streamUrl,
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

    fun toAlbum(album: dev.brahmkshatriya.echo.extension.models.DabAlbum): Album {
        return Album(
            id = album.id,
            title = album.title,
            cover = album.cover?.toImageHolder(),
            artists = listOf(Artist(id = album.artistId?.toString() ?: album.artist, name = album.artist)),
            releaseDate = null, // DAB API returns string date, Echo expects Date - convert if needed
            trackCount = album.trackCount.toLong()
        )
    }

    fun toArtist(artist: dev.brahmkshatriya.echo.extension.models.DabArtist): Artist {
        return Artist(
            id = artist.id.toString(),
            name = artist.name,
            cover = artist.picture?.toImageHolder()
        )
    }

    // Convert a raw lyrics string (plain or LRC) into the Echo Lyrics.Lyric model
    fun toLyricFromText(text: String?): Lyrics.Lyric? {
        // Convert raw lyric text (LRC, JSON-shaped, or plain) into Lyrics.Lyric
        if (text == null) return null
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        // LRC parsing
        // Custom lightweight LRC parser to avoid calling the platform's fromText (which may be missing)
        try {
            val lrcLineRegex = Regex("\\[(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?](.*)")
            val matches = lrcLineRegex.findAll(trimmed).toList()
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
        // Heuristics: if elements have time/start + text -> Timed
        // If elements have words arrays with timing -> WordByWord
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

        // hh:mm:ss.mmm or mm:ss.mmm
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

        // Patterns to remove (global):
        // - [mm:ss[.ms]] or (mm:ss[.ms])
        // - mm:ss[.ms] anywhere (with optional leading/trailing separators)
        // - H:mm:ss forms
        // - timestamps followed by separators like " - " or ": "

        // Remove bracketed or parenthesized timestamps (e.g. [mm:ss], (mm:ss.ms))
        // Use a character class for opening brackets and allow either ')' or ']' as closing bracket.
        s = s.replace(Regex("""[\[(]\s*\d{1,2}:\d{2}(?:[.:]\d{1,3})?\s*[)\]]"""), " ")

        // Remove full hour:minute:second timestamps like 1:02:03 or 01:02:03.456
        s = s.replace(Regex("\\b\\d{1,2}:\\d{2}:\\d{2}(?:[.:]\\d{1,3})?\\b"), " ")

        // Remove mm:ss(.ms) occurrences anywhere
        s = s.replace(Regex("\\b\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?\\b"), " ")

        // Remove common separators left over like leading dashes or colons at line starts
        s = s.lines().joinToString("\n") { line ->
            line.replace(Regex("^[\\s-:]+|[\\s-:]+$") , "").trim()
        }

        // Collapse multiple spaces/newlines and trim
        s = s.replace(Regex("[\t ]+"), " ")
        s = s.lines().joinToString("\n") { it.trim() }
        s = s.replace(Regex("(?:\\n\\s*){2,}"), "\n")
        return s.trim()
    }

    // Public helper: remove timestamps and clean text for UI display
    fun cleanPlainText(text: String?): String {
        if (text == null) return ""
        return stripLrcTimestamps(text)
    }
}