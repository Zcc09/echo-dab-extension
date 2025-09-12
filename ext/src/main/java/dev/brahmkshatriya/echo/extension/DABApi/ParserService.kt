package dev.brahmkshatriya.echo.extension.dabapi

import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.Converter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement

class ParserService(
    private val json: Json,
    private val converter: Converter
) {
    // Walk arbitrary JSON and extract Track objects
    fun parseTracksFromJsonElement(root: JsonElement?): List<Track> {
        if (root == null) return emptyList()
        val out = mutableListOf<Track>()

        fun visit(el: JsonElement) {
            when (el) {
                is JsonObject -> {
                    // Try direct track object
                    val direct = tryParseElementAsTrack(el)
                    if (direct != null) {
                        out.add(converter.toTrack(direct))
                        return
                    }

                    val candidates = listOf("favorites", "tracks", "data", "items", "results")
                    for (k in candidates) {
                        val v = el[k]
                        if (v is JsonArray) {
                            for (itEl in v) {
                                if (itEl is JsonObject) {
                                    val parsed = tryParseElementAsTrack(itEl)
                                    if (parsed != null) out.add(converter.toTrack(parsed)) else visit(itEl)
                                } else visit(itEl)
                            }
                            if (out.isNotEmpty()) return
                        }
                    }

                    for ((_, v) in el) {
                        visit(v)
                        if (out.isNotEmpty()) return
                    }
                }
                is JsonArray -> {
                    for (item in el) {
                        if (item is JsonObject) {
                            val parsed = tryParseElementAsTrack(item)
                            if (parsed != null) { out.add(converter.toTrack(parsed)); continue }
                        }
                        visit(item)
                    }
                }
                else -> {}
            }
        }

        try { visit(root) } catch (_: Throwable) {}
        return out
    }

    // Expose parsing helper for single JsonObject -> DabTrack? conversion
    fun tryParseElementAsTrack(el: JsonObject): dev.brahmkshatriya.echo.extension.models.DabTrack? {
        try {
            return try {
                json.decodeFromJsonElement(dev.brahmkshatriya.echo.extension.models.DabTrack.serializer(), el)
            } catch (_: Throwable) {
                // manual fallback
                val idEl = el["id"] ?: el["trackId"] ?: el["track_id"] ?: el["track"] ?: return null
                val idStr = (idEl as? JsonPrimitive)?.content ?: idEl.toString()
                val idInt = idStr.toIntOrNull() ?: return null
                val title = (el["title"] as? JsonPrimitive)?.content ?: (el["name"] as? JsonPrimitive)?.content ?: ""
                val artist = (el["artist"] as? JsonPrimitive)?.content ?: (el["artistName"] as? JsonPrimitive)?.content ?: ""
                val artistId = (el["artistId"] as? JsonPrimitive)?.content?.toIntOrNull()
                val albumTitle = (el["albumTitle"] as? JsonPrimitive)?.content ?: (el["album"] as? JsonPrimitive)?.content
                val albumCover = (el["albumCover"] as? JsonPrimitive)?.content
                val albumId = (el["albumId"] as? JsonPrimitive)?.content
                val releaseDate = (el["releaseDate"] as? JsonPrimitive)?.content
                val genre = (el["genre"] as? JsonPrimitive)?.content
                val duration = (el["duration"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                return dev.brahmkshatriya.echo.extension.models.DabTrack(
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
            }
        } catch (_: Throwable) { return null }
    }
}
