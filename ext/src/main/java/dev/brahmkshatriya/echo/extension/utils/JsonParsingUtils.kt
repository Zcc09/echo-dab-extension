package dev.brahmkshatriya.echo.extension.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import dev.brahmkshatriya.echo.extension.models.DabTrack
import dev.brahmkshatriya.echo.extension.Converter
import dev.brahmkshatriya.echo.common.models.Track

/**
 * Utility class for parsing DAB API JSON responses with consolidated parsing logic
 */
class JsonParsingUtils(
    private val json: Json,
    private val converter: Converter
) {

    /** Parse tracks from various JSON response formats */
    fun parseTracksFromResponse(root: JsonElement): List<Track> {
        val parsed = mutableListOf<Track>()

        when (root) {
            is JsonObject -> {
                val candidates = listOf("tracks", "data", "items", "results", "favorites", "library", "content")
                for (key in candidates) {
                    val arr = root[key] as? JsonArray
                    if (arr != null) {
                        for (el in arr) if (el is JsonObject) {
                            val track = tryParseTrackFromJsonObject(el)
                            if (track != null) parsed.add(track)
                        }
                        if (parsed.isNotEmpty()) break
                    }
                }
            }
            is JsonArray -> {
                for (el in root) if (el is JsonObject) {
                    val track = tryParseTrackFromJsonObject(el)
                    if (track != null) parsed.add(track)
                }
            }
            else -> { }
        }
        return parsed
    }

    /** Parse a single track from JSON object with fallback logic */
    fun tryParseTrackFromJsonObject(el: JsonObject): Track? {
        return try {
            // Try direct deserialization first
            val dt = json.decodeFromJsonElement(DabTrack.serializer(), el)
            converter.toTrack(dt)
        } catch (_: Throwable) {
            // Fallback to manual parsing
            val dabTrack = tryParseManualTrack(el)
            if (dabTrack != null) converter.toTrack(dabTrack) else null
        }
    }

    /** Parse track from JSON object manually with consistent field extraction */
    private fun tryParseManualTrack(el: JsonObject): DabTrack? {
        return try {
            val idEl = el["id"] ?: el["trackId"] ?: el["track_id"] ?: el["track"] ?: return null
            val idStr = (idEl as? JsonPrimitive)?.content ?: idEl.toString()
            val idInt = idStr.toIntOrNull() ?: return null

            val title = extractStringField(el, "title", "name") ?: ""
            val artist = extractStringField(el, "artist", "artistName") ?: ""
            val artistId = extractStringField(el, "artistId")?.toIntOrNull()
            val albumTitle = extractStringField(el, "albumTitle", "album")
            val albumCover = extractStringField(el, "albumCover", "cover")
            val albumId = extractStringField(el, "albumId")
            val releaseDate = extractStringField(el, "releaseDate")
            val genre = extractStringField(el, "genre")
            val duration = extractStringField(el, "duration")?.toIntOrNull() ?: 0

            DabTrack(
                id = idInt,
                title = title,
                artist = artist,
                artistId = artistId,
                albumTitle = albumTitle,
                albumCover = albumCover,
                albumId = albumId,
                releaseDate = releaseDate,
                genre = genre,
                duration = duration,
                audioQuality = null
            )
        } catch (_: Throwable) { null }
    }

    /** Extract string field from JSON object trying multiple field names */
    private fun extractStringField(obj: JsonObject, vararg fieldNames: String): String? {
        for (fieldName in fieldNames) {
            val value = (obj[fieldName] as? JsonPrimitive)?.content
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    /** Generic JSON traversal for finding arrays in complex structures */
    fun findArrayInJson(root: JsonElement, arrayKeys: List<String>): JsonArray? {
        when (root) {
            is JsonObject -> {
                for (key in arrayKeys) {
                    val arr = root[key] as? JsonArray
                    if (arr != null) return arr
                }
            }
            is JsonArray -> return root
            else -> return null
        }
        return null
    }
}
