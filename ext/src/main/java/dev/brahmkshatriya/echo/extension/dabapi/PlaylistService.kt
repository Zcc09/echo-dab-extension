package dev.brahmkshatriya.echo.extension.dabapi

import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Artist
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import okhttp3.OkHttpClient
import dev.brahmkshatriya.echo.common.settings.Settings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.brahmkshatriya.echo.extension.Converter
import dev.brahmkshatriya.echo.extension.utils.RequestUtils
import dev.brahmkshatriya.echo.extension.utils.JsonParsingUtils
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class PlaylistService(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val converter: Converter,
    private val settings: Settings
) {
    // Use consolidated utilities
    private val requestUtils: RequestUtils by lazy { RequestUtils(settings) }
    private val jsonParsingUtils: JsonParsingUtils by lazy { JsonParsingUtils(json, converter) }

    private val playlistCache = mutableMapOf<String, Pair<Long, List<Track>>>()
    private val CACHE_TTL_MS = 2 * 60 * 1000L
    private var playlistsCache: Pair<Long, List<Playlist>>? = null
    private val PLAYLISTS_CACHE_TTL_MS = 60 * 1000L

    /** Get cached tracks for playlist if valid */
    fun getCachedTracksForPlaylist(playlistId: String): List<Track>? {
        if (!requestUtils.isLoggedIn()) {
            playlistCache.clear()
            return null
        }

        val entry = playlistCache[playlistId] ?: return null
        return if (System.currentTimeMillis() - entry.first <= CACHE_TTL_MS) entry.second else null
    }

    /** Fetch tracks for playlist with caching */
    suspend fun fetchTracksForPlaylistSync(playlist: Playlist, pageIndex: Int = 1, pageSize: Int = 1000): List<Track> {
        return withContext(Dispatchers.IO) {
            if (!requestUtils.isLoggedIn()) {
                playlistCache.clear()
                return@withContext emptyList()
            }

            val now = System.currentTimeMillis()
            val cacheEntry = playlistCache[playlist.id]
            if (cacheEntry != null && now - cacheEntry.first <= CACHE_TTL_MS) {
                return@withContext cacheEntry.second
            }

            val correctEndpoint = "https://dab.yeet.su/api/libraries/${URLEncoder.encode(playlist.id, "UTF-8")}".trim()
            val requestUrl = "$correctEndpoint?page=$pageIndex&limit=$pageSize"

            val clientRobust = httpClient.newBuilder().callTimeout(10000, TimeUnit.MILLISECONDS).build()

            try {
                val req = requestUtils.newRequestBuilder(requestUrl).build()
                clientRobust.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        requestUtils.clearSessionOnAuthFailure(resp.code)
                        if (resp.code == 401 || resp.code == 403) {
                            playlistCache.clear()
                            playlistsCache = null
                        }
                        return@withContext emptyList()
                    }

                    val body = resp.body?.string()
                    if (body.isNullOrEmpty()) {
                        return@withContext emptyList()
                    }

                    val tracks = parseLibraryResponse(body)

                    if (tracks.isNotEmpty()) {
                        playlistCache[playlist.id] = now to tracks
                        return@withContext tracks
                    }
                }
            } catch (_: Throwable) { }

            playlistCache[playlist.id] = now to emptyList()
            emptyList()
        }
    }

    /** Parse DAB library response to tracks using consolidated utilities */
    private fun parseLibraryResponse(body: String): List<Track> {
        try {
            val response: dev.brahmkshatriya.echo.extension.models.DabLibraryResponse = json.decodeFromString(body)
            response.library?.tracks?.let { tracks ->
                return tracks.mapNotNull { track ->
                    try { converter.toTrack(track) } catch (_: Throwable) { null }
                }
            }
        } catch (_: Throwable) { }

        // Fallback to consolidated parsing
        try {
            val root = json.parseToJsonElement(body)
            return jsonParsingUtils.parseTracksFromResponse(root)
        } catch (_: Throwable) { }

        return emptyList()
    }

    /** Fetch library playlists with pagination */
    fun fetchLibraryPlaylistsPage(pageIndex: Int = 1, pageSize: Int = 50, includeCovers: Boolean = true): List<Playlist> {
        if (!requestUtils.isLoggedIn()) {
            playlistsCache = null
            return emptyList()
        }

        val now = System.currentTimeMillis()
        val cached = playlistsCache
        if (cached != null && now - cached.first <= PLAYLISTS_CACHE_TTL_MS) return cached.second

        val candidates = listOf(
            "https://dab.yeet.su/api/libraries?page=$pageIndex&limit=$pageSize",
            "https://dab.yeet.su/api/playlists?page=$pageIndex&limit=$pageSize",
            "https://dab.yeet.su/api/library?page=$pageIndex&limit=$pageSize"
        )

        for (url in candidates) {
            try {
                val req = requestUtils.newRequestBuilder(url).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        requestUtils.clearSessionOnAuthFailure(resp.code)
                        if (resp.code == 401 || resp.code == 403) {
                            playlistsCache = null
                            playlistCache.clear()
                        }
                        continue
                    }
                    val body = resp.body?.string() ?: continue

                    val playlists = parsePlaylistsFromBody(body)
                    if (playlists.isNotEmpty()) {
                        if (includeCovers) augmentPlaylistsWithCovers(playlists.toMutableList())
                        playlistsCache = now to playlists
                        return playlists
                    }
                }
            } catch (_: Throwable) { }
        }

        playlistsCache = now to emptyList()
        return emptyList()
    }

    /** Parse playlists from response body */
    private fun parsePlaylistsFromBody(body: String): List<Playlist> {
        try {
            val root = json.parseToJsonElement(body)
            val list = mutableListOf<Playlist>()

            val arr = jsonParsingUtils.findArrayInJson(
                root,
                listOf("libraries", "playlists", "data", "items", "results")
            )

            arr?.forEach { el ->
                if (el is JsonObject) {
                    try {
                        val dp = json.decodeFromJsonElement(dev.brahmkshatriya.echo.extension.models.DabPlaylist.serializer(), el)
                        list.add(converter.toPlaylist(dp))
                    } catch (_: Throwable) {
                        val id = extractStringValue(el, "id", "playlistId", "libraryId", "slug")
                        val title = extractStringValue(el, "name", "title", "label") ?: "Playlist"
                        val tc = extractStringValue(el, "trackCount", "count")?.toIntOrNull() ?: 0

                        if (!id.isNullOrBlank()) {
                            list.add(Playlist(
                                id = id,
                                title = title,
                                authors = listOf(Artist(id = "user", name = "You")),
                                isEditable = false,
                                trackCount = tc.toLong(),
                                cover = null
                            ))
                        }
                    }
                }
            }
            return list
        } catch (_: Throwable) { }
        return emptyList()
    }

    /** Extract string value from JSON object by trying multiple keys */
    private fun extractStringValue(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val value = (obj[key] as? JsonPrimitive)?.content
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    /** Add covers to playlists from their tracks */
    private fun augmentPlaylistsWithCovers(playlists: MutableList<Playlist>) {
        for (i in playlists.indices) {
            val p = playlists[i]
            try {
                val cached = getCachedTracksForPlaylist(p.id)
                val tracks = cached ?: try {
                    runBlocking { fetchTracksForPlaylistSync(p) }
                } catch (_: Throwable) { emptyList() }

                val latestCover = tracks.firstOrNull()?.cover
                if (latestCover != null) {
                    playlists[i] = p.copy(cover = latestCover)
                }
            } catch (_: Throwable) { }
        }
    }

    fun invalidateCaches() {
        playlistsCache = null
        playlistCache.clear()
    }

    // Create a new playlist (library)
    fun createPlaylist(title: String, description: String?): Playlist {
        val bodyJson = buildString {
            append('{')
            append("\"name\":\"").append(title.replace("\"", "\\\"")).append('"')
            if (!description.isNullOrBlank()) {
                append(',')
                append("\"description\":\"").append(description.replace("\"", "\\\"")).append('"')
            }
            append('}')
        }
        val req = requestUtils.newRequestBuilder("https://dab.yeet.su/api/libraries")
            .post(bodyJson.toRequestBody("application/json".toMediaType())).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                requestUtils.clearSessionOnAuthFailure(resp.code)
                error("Failed to create playlist: ${resp.code}")
            }
            val body = resp.body?.string() ?: error("Empty create playlist response")
            val playlist = parseSinglePlaylist(body) ?: error("Failed to parse created playlist")
            invalidateCaches()
            return playlist
        }
    }

    // Delete a playlist
    fun deletePlaylist(playlist: Playlist) {
        val req = requestUtils.newRequestBuilder("https://dab.yeet.su/api/libraries/${URLEncoder.encode(playlist.id, "UTF-8")}")
            .delete().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                requestUtils.clearSessionOnAuthFailure(resp.code)
                error("Failed to delete playlist: ${resp.code}")
            }
            invalidateCaches()
        }
    }

    // Edit playlist metadata
    fun editPlaylistMetadata(playlist: Playlist, title: String, description: String?) : Playlist {
        val bodyJson = buildString {
            append('{')
            append("\"name\":\"").append(title.replace("\"", "\\\"")).append('"')
            if (description != null) {
                append(',')
                append("\"description\":")
                if (description.isBlank()) append("null") else append("\"").append(description.replace("\"", "\\\"")).append('"')
            }
            append('}')
        }
        val req = requestUtils.newRequestBuilder("https://dab.yeet.su/api/libraries/${URLEncoder.encode(playlist.id, "UTF-8")}")
            .patch(bodyJson.toRequestBody("application/json".toMediaType())).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                requestUtils.clearSessionOnAuthFailure(resp.code)
                error("Failed to edit playlist: ${resp.code}")
            }
            val body = resp.body?.string() ?: return playlist
            val updated = parseSinglePlaylist(body) ?: playlist.copy(title = title)
            invalidateCaches()
            return updated
        }
    }

    // Add tracks to playlist (sequentially)
    fun addTracks(playlist: Playlist, new: List<Track>) {
        if (new.isEmpty()) return
        for (t in new) {
            try {
                val inner = converter.toDabTrackJson(t).toString()
                val wrapper = "{\"track\":$inner}"
                val req = requestUtils.newRequestBuilder("https://dab.yeet.su/api/libraries/${URLEncoder.encode(playlist.id, "UTF-8")}/tracks")
                    .post(wrapper.toRequestBody("application/json".toMediaType())).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        requestUtils.clearSessionOnAuthFailure(resp.code)
                    }
                }
            } catch (_: Throwable) { }
        }
        playlistCache.remove(playlist.id)
    }

    // Remove tracks by id
    fun removeTracks(playlist: Playlist, tracks: List<Track>, indexes: List<Int>) {
        if (indexes.isEmpty()) return
        val uniqueIds = indexes.mapNotNull { tracks.getOrNull(it)?.id }.distinct()
        for (id in uniqueIds) {
            try {
                val url = "https://dab.yeet.su/api/libraries/${URLEncoder.encode(playlist.id, "UTF-8")}/tracks/${URLEncoder.encode(id, "UTF-8") }"
                val req = requestUtils.newRequestBuilder(url).delete().build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        requestUtils.clearSessionOnAuthFailure(resp.code)
                    }
                }
            } catch (_: Throwable) { }
        }
        playlistCache.remove(playlist.id)
    }

    // Parse a single playlist from body
    private fun parseSinglePlaylist(body: String): Playlist? {
        try {
            val root = json.parseToJsonElement(body)
            if (root is JsonObject) {
                val libraryObj = (root["library"] as? JsonObject) ?: root
                val id = (libraryObj["id"] as? JsonPrimitive)?.content ?: return null
                val name = (libraryObj["name"] as? JsonPrimitive)?.content
                    ?: (libraryObj["title"] as? JsonPrimitive)?.content ?: return null
                val trackCount = (libraryObj["trackCount"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                return Playlist(
                    id = id,
                    title = name,
                    authors = listOf(Artist(id = "user", name = "You")),
                    isEditable = true,
                    trackCount = trackCount.toLong(),
                    cover = null
                )
            }
        } catch (_: Throwable) { }
        return parsePlaylistsFromBody(body).firstOrNull()
    }
}
