package dev.brahmkshatriya.echo.extension.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import dev.brahmkshatriya.echo.extension.models.DabTrack
import dev.brahmkshatriya.echo.extension.models.DabAlbum
import dev.brahmkshatriya.echo.extension.models.DabArtist
import dev.brahmkshatriya.echo.extension.models.DabSearchResponse
import dev.brahmkshatriya.echo.extension.models.DabSearchResult
import dev.brahmkshatriya.echo.extension.Converter
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist

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

    /** Parse DabAlbum (with embedded tracks if present) from a JSON element */
    fun parseAlbum(root: JsonElement): DabAlbum? {
        return try {
            when (root) {
                is JsonObject -> {
                    val albumObj = root["album"] as? JsonObject ?: root
                    val id = (albumObj["id"] as? JsonPrimitive)?.content
                        ?: (albumObj["albumId"] as? JsonPrimitive)?.content ?: return null
                    val title = (albumObj["title"] as? JsonPrimitive)?.content
                        ?: (albumObj["name"] as? JsonPrimitive)?.content ?: "Album"
                    val artist = (albumObj["artist"] as? JsonPrimitive)?.content
                        ?: (albumObj["artistName"] as? JsonPrimitive)?.content ?: ""
                    val artistId = (albumObj["artistId"] as? JsonPrimitive)?.content?.toIntOrNull()
                    val cover = (albumObj["cover"] as? JsonPrimitive)?.content
                        ?: (albumObj["albumCover"] as? JsonPrimitive)?.content
                    val releaseDate = (albumObj["releaseDate"] as? JsonPrimitive)?.content
                    val trackCount = (albumObj["trackCount"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

                    val tracks = mutableListOf<DabTrack>()
                    val tracksArray = albumObj["tracks"] as? JsonArray
                    tracksArray?.forEach { trackEl ->
                        if (trackEl is JsonObject) {
                            runCatching { json.decodeFromJsonElement(DabTrack.serializer(), trackEl) }
                                .onSuccess { tracks.add(it) }
                                .onFailure {
                                    val manual = tryParseManualTrack(trackEl)
                                    if (manual != null) tracks.add(manual)
                                }
                        }
                    }
                    DabAlbum(
                        id = id,
                        title = title,
                        artist = artist,
                        artistId = artistId,
                        cover = cover,
                        releaseDate = releaseDate,
                        trackCount = trackCount,
                        tracks = if (tracks.isNotEmpty()) tracks else null
                    )
                }
                else -> null
            }
        } catch (_: Throwable) { null }
    }

    /** Parse DabArtist from JSON element */
    fun parseArtist(root: JsonElement): DabArtist? {
        return try {
            if (root !is JsonObject) return null
            val artistObj = root["artist"] as? JsonObject ?: root
            val idEl = artistObj["id"] ?: artistObj["artistId"] ?: return null
            val idStr = (idEl as? JsonPrimitive)?.content ?: idEl.toString()
            val idInt = idStr.toIntOrNull() ?: return null
            val name = (artistObj["name"] as? JsonPrimitive)?.content
                ?: (artistObj["title"] as? JsonPrimitive)?.content
                ?: (artistObj["artistName"] as? JsonPrimitive)?.content ?: "Artist"
            val picture = (artistObj["picture"] as? JsonPrimitive)?.content
                ?: (artistObj["cover"] as? JsonPrimitive)?.content
                ?: (artistObj["image"] as? JsonPrimitive)?.content
            DabArtist(id = idInt, name = name, picture = picture)
        } catch (_: Throwable) { null }
    }

    /** Parse list of DabAlbum in discography responses */
    fun parseDiscography(root: JsonElement): List<DabAlbum> {
        val out = mutableListOf<DabAlbum>()
        try {
            when (root) {
                is JsonObject -> {
                    val candidates = listOf("albums", "discography", "data", "items", "results", "content")
                    for (key in candidates) {
                        val arr = root[key] as? JsonArray ?: continue
                        arr.forEach { el ->
                            if (el is JsonObject) {
                                runCatching { json.decodeFromJsonElement(DabAlbum.serializer(), el) }
                                    .onSuccess { out.add(it) }
                                    .onFailure { parseAlbum(el)?.let(out::add) }
                            }
                        }
                        if (out.isNotEmpty()) break
                    }
                    if (out.isEmpty()) {
                        runCatching { json.decodeFromJsonElement(DabAlbum.serializer(), root) }
                            .onSuccess { out.add(it) }
                            .onFailure { parseAlbum(root)?.let(out::add) }
                    }
                }
                is JsonArray -> {
                    root.forEach { el ->
                        if (el is JsonObject) {
                            runCatching { json.decodeFromJsonElement(DabAlbum.serializer(), el) }
                                .onSuccess { out.add(it) }
                                .onFailure { parseAlbum(el)?.let(out::add) }
                        }
                    }
                }
                else -> { /* no-op for JsonPrimitive subclasses */ }
            }
        } catch (_: Throwable) { }
        return out
    }

    /** Parse search results body for a specific type (track / album / artist) */
    fun parseSearchResults(body: String, type: String): List<Any> {
        // Try specialized unified parser first; fallback to generic mapping
        return parseSearchResponseInternal(body) { obj ->
            when (type) {
                "track" -> parseTrackResult(obj)
                "album" -> parseAlbumResult(obj)
                "artist" -> parseArtistResult(obj)
                else -> null
            }
        }
    }

    /** Attempt unified search parse returning triple */
    fun parseUnifiedSearch(body: String): Triple<List<Track>, List<Album>, List<Artist>>? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val tracks = mutableListOf<Track>()
        val albums = mutableListOf<Album>()
        val artists = mutableListOf<Artist>()
        root["tracks"]?.let { tracks.addAll(parseSearchResponseInternalElement(it, ::parseTrackResult).filterIsInstance<Track>()) }
        root["albums"]?.let { albums.addAll(parseSearchResponseInternalElement(it, ::parseAlbumResult).filterIsInstance<Album>()) }
        root["artists"]?.let { artists.addAll(parseSearchResponseInternalElement(it, ::parseArtistResult).filterIsInstance<Artist>()) }
        if (tracks.isNotEmpty() || albums.isNotEmpty() || artists.isNotEmpty()) return Triple(tracks, albums, artists)
        return null
    }

    /** Extract unique artists from a track search JSON body */
    fun extractArtistsFromTrackSearch(body: String): List<Artist> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val tracksArray = root["tracks"] as? JsonArray ?: return emptyList()
        val out = LinkedHashMap<String, Artist>()
        tracksArray.forEach { el ->
            if (el !is JsonObject) return@forEach
            val artistName = (el["artist"] as? JsonPrimitive)?.content
                ?: (el["artistName"] as? JsonPrimitive)?.content
            val artistIdStr = (el["artistId"] as? JsonPrimitive)?.content
            val albumCover = (el["albumCover"] as? JsonPrimitive)?.content
            if (!artistName.isNullOrBlank() && !artistIdStr.isNullOrBlank()) {
                val idInt = artistIdStr.toIntOrNull()
                if (idInt != null && !out.containsKey(artistIdStr)) {
                    val dabArtist = DabArtist(id = idInt, name = artistName, picture = albumCover)
                    out[artistIdStr] = converter.toArtist(dabArtist)
                }
            }
        }
        return out.values.toList()
    }

    // ---- Internal search parsing helpers ----
    private fun parseSearchResponseInternal(body: String, parser: (JsonObject) -> Any?): List<Any> {
        // 1. object with results or type-specific arrays
        runCatching {
            val root = json.parseToJsonElement(body) as? JsonObject
            if (root != null) {
                val resultsArray = root["results"] as? JsonArray
                if (resultsArray != null) {
                    val list = resultsArray.mapNotNull { if (it is JsonObject) parser(it) else null }
                    if (list.isNotEmpty()) return list
                }
                val typeArrays = listOf("tracks", "songs", "albums", "releases", "artists", "performers")
                for (key in typeArrays) {
                    val arr = root[key] as? JsonArray ?: continue
                    val list = arr.mapNotNull { if (it is JsonObject) parser(it) else null }
                    if (list.isNotEmpty()) return list
                }
            }
        }
        // 2. structured DabSearchResponse
        runCatching {
            val searchResp = json.decodeFromString(DabSearchResponse.serializer(), body)
            val list = searchResp.results.mapNotNull {
                val element = json.encodeToJsonElement(DabSearchResult.serializer(), it) as? JsonObject
                if (element != null) parser(element) else null
            }
            if (list.isNotEmpty()) return list
        }
        // 3. raw array
        runCatching {
            val arr = json.parseToJsonElement(body) as? JsonArray
            if (arr != null) {
                val list = arr.mapNotNull { if (it is JsonObject) parser(it) else null }
                if (list.isNotEmpty()) return list
            }
        }
        // 4. single item
        runCatching {
            val single = json.parseToJsonElement(body) as? JsonObject
            if (single != null) {
                val v = parser(single)
                if (v != null) return listOf(v)
            }
        }
        return emptyList()
    }

    private fun parseSearchResponseInternalElement(root: JsonElement, parser: (JsonObject) -> Any?): List<Any> {
        return when (root) {
            is JsonArray -> root.mapNotNull { if (it is JsonObject) parser(it) else null }
            is JsonObject -> listOfNotNull(parser(root))
            else -> emptyList()
        }
    }

    private fun parseTrackResult(el: JsonObject): Track? = tryParseTrackFromJsonObject(el)
    private fun parseAlbumResult(el: JsonObject): Album? {
        return try {
            val dabAlbum = json.decodeFromJsonElement(DabAlbum.serializer(), el)
            converter.toAlbum(dabAlbum)
        } catch (_: Throwable) {
            parseAlbum(el)?.let { converter.toAlbum(it) }
        }
    }
    private fun parseArtistResult(el: JsonObject): Artist? {
        return try {
            val dabArtist = json.decodeFromJsonElement(DabArtist.serializer(), el)
            converter.toArtist(dabArtist)
        } catch (_: Throwable) {
            parseArtist(el)?.let { converter.toArtist(it) }
        }
    }
}
