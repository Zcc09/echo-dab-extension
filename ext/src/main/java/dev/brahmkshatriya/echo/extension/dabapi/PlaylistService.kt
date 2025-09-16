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
    companion object {
        private const val LIBRARIES_BASE = "https://dab.yeet.su/api/libraries"
        private const val PLAYLISTS_BASE = "https://dab.yeet.su/api/playlists"
        private const val LIBRARY_FALLBACK = "https://dab.yeet.su/api/library"
        private val PLAYLIST_LIST_ENDPOINTS = listOf(LIBRARIES_BASE, PLAYLISTS_BASE, LIBRARY_FALLBACK)
        private const val TRACKS_SEGMENT = "/tracks"
        private const val CACHE_TTL_MS = 120_000L
        private const val PLAYLISTS_CACHE_TTL_MS = 60_000L
    }

    // Use consolidated utilities
    private val requestUtils: RequestUtils by lazy { RequestUtils(settings) }
    private val jsonParsingUtils: JsonParsingUtils by lazy { JsonParsingUtils(json, converter) }

    private val playlistCache = mutableMapOf<String, Pair<Long, List<Track>>>()
    private var playlistsCache: Pair<Long, List<Playlist>>? = null

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun within(ts: Long, ttl: Long) = (System.currentTimeMillis() - ts) <= ttl

    private fun newRequest(url: String) = requestUtils.newRequestBuilder(url).build()

    private inline fun <R> execute(url: String, onFail: () -> R, block: (String) -> R): R {
        return try {
            httpClient.newCall(newRequest(url)).execute().use { resp ->
                if (!resp.isSuccessful) {
                    requestUtils.clearSessionOnAuthFailure(resp.code); return onFail()
                }
                val body = resp.body?.string() ?: return onFail()
                block(body)
            }
        } catch (_: Throwable) { onFail() }
    }

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
                playlistCache.clear(); return@withContext emptyList()
            }
            val cached = playlistCache[playlist.id]
            if (cached != null && within(cached.first, CACHE_TTL_MS)) return@withContext cached.second

            val url = "$LIBRARIES_BASE/${enc(playlist.id)}?page=$pageIndex&limit=$pageSize"
            val now = System.currentTimeMillis()
            val tracks = execute(url, { emptyList() }) { parseLibraryResponse(it) }
            playlistCache[playlist.id] = now to tracks
            tracks
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
        if (!requestUtils.isLoggedIn()) { playlistsCache = null; return emptyList() }
        playlistsCache?.let { (ts, list) -> if (within(ts, PLAYLISTS_CACHE_TTL_MS)) return list }

        val querySuffix = "?page=$pageIndex&limit=$pageSize"
        var result: List<Playlist> = emptyList()
        for (base in PLAYLIST_LIST_ENDPOINTS) {
            val url = base + querySuffix
            result = execute(url, { emptyList() }) { parsePlaylistsFromBody(it) }
            if (result.isNotEmpty()) break
        }
        if (result.isNotEmpty() && includeCovers) augmentPlaylistsWithCovers(result.toMutableList())
        playlistsCache = System.currentTimeMillis() to result
        return result
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
            append('{'); append("\"name\":\"").append(title.replace("\"", "\\\"")).append('"')
            if (!description.isNullOrBlank()) append(",\"description\":\"").append(description.replace("\"", "\\\"")).append('"')
            append('}')
        }
        val url = LIBRARIES_BASE
        return try {
            httpClient.newCall(
                requestUtils.newRequestBuilder(url)
                    .post(bodyJson.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) { requestUtils.clearSessionOnAuthFailure(resp.code); error("Failed to create playlist: ${resp.code}") }
                val body = resp.body?.string() ?: error("Empty create playlist response")
                val playlist = parseSinglePlaylist(body) ?: error("Failed to parse created playlist")
                invalidateCaches(); playlist
            }
        } catch (e: Throwable) { error("Failed to create playlist: ${e.message}") }
    }

    // Simplify: use direct request (execute helper above tailored for GET; keep original logic for writes)
    fun deletePlaylist(playlist: Playlist) {
        val url = "$LIBRARIES_BASE/${enc(playlist.id)}"
        try {
            httpClient.newCall(requestUtils.newRequestBuilder(url).delete().build()).execute().use { resp ->
                if (!resp.isSuccessful) { requestUtils.clearSessionOnAuthFailure(resp.code); error("Failed to delete playlist: ${resp.code}") }
                invalidateCaches()
            }
        } catch (e: Throwable) { error("Failed to delete playlist: ${e.message}") }
    }

    // Edit playlist metadata
    fun editPlaylistMetadata(playlist: Playlist, title: String, description: String?) : Playlist {
        val bodyJson = buildString {
            append('{'); append("\"name\":\"").append(title.replace("\"", "\\\"")).append('\"')
            append(','); append("\"description\":"); if (description == null) append("null") else append("\"").append(description.replace("\"", "\\\"")).append("\"")
            append('}')
        }
        val url = "$LIBRARIES_BASE/${enc(playlist.id)}"
        return try {
            httpClient.newCall(requestUtils.newRequestBuilder(url).patch(bodyJson.toRequestBody("application/json".toMediaType())).build()).execute().use { resp ->
                if (!resp.isSuccessful) { requestUtils.clearSessionOnAuthFailure(resp.code); error("Failed to edit playlist: ${resp.code}") }
                val body = resp.body?.string(); val updated = body?.let { parseSinglePlaylist(it) }
                invalidateCaches(); updated ?: playlist.copy(title = title)
            }
        } catch (_: Throwable) { playlist.copy(title = title) }
    }

    // Add tracks to playlist (sequentially)
    fun addTracks(playlist: Playlist, new: List<Track>) {
        if (new.isEmpty()) return
        val url = "$LIBRARIES_BASE/${enc(playlist.id)}$TRACKS_SEGMENT"
        new.forEach { t ->
            try {
                val inner = converter.toDabTrackJson(t).toString()
                val wrapper = "{\"track\":$inner}"
                httpClient.newCall(requestUtils.newRequestBuilder(url).post(wrapper.toRequestBody("application/json".toMediaType())).build()).execute().use { resp ->
                    if (!resp.isSuccessful) requestUtils.clearSessionOnAuthFailure(resp.code)
                }
            } catch (_: Throwable) { }
        }
        playlistCache.remove(playlist.id)
    }

    // Remove tracks by id
    fun removeTracks(playlist: Playlist, tracks: List<Track>, indexes: List<Int>) {
        if (indexes.isEmpty()) return
        val uniqueIds = indexes.mapNotNull { tracks.getOrNull(it)?.id }.distinct()
        uniqueIds.forEach { id ->
            val url = "$LIBRARIES_BASE/${enc(playlist.id)}$TRACKS_SEGMENT/${enc(id)}"
            try {
                httpClient.newCall(requestUtils.newRequestBuilder(url).delete().build()).execute().use { resp ->
                    if (!resp.isSuccessful) requestUtils.clearSessionOnAuthFailure(resp.code)
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
