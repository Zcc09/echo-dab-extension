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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.brahmkshatriya.echo.extension.Converter
import dev.brahmkshatriya.echo.extension.utils.RequestUtils
import dev.brahmkshatriya.echo.extension.utils.JsonParsingUtils
import dev.brahmkshatriya.echo.extension.utils.ApiConstants
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
        private val LIBRARIES_BASE = ApiConstants.api("libraries")
        private val PLAYLISTS_BASE = ApiConstants.api("playlists")
        private val LIBRARY_FALLBACK = ApiConstants.api("library")
        private val PLAYLIST_LIST_ENDPOINTS = listOf(LIBRARIES_BASE, PLAYLISTS_BASE, LIBRARY_FALLBACK)
        private const val TRACKS_SEGMENT = "/tracks"
    }

    private val requestUtils: RequestUtils by lazy { RequestUtils(settings) }
    private val jsonParsingUtils: JsonParsingUtils by lazy { JsonParsingUtils(json, converter) }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

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

    /** Always fetch tracks fresh for a playlist */
    suspend fun fetchTracksForPlaylistSync(playlist: Playlist, pageIndex: Int = 1, pageSize: Int = 1000): List<Track> {
        return withContext(Dispatchers.IO) {
            if (!requestUtils.isLoggedIn()) return@withContext emptyList()
            val url = "${LIBRARIES_BASE}/${enc(playlist.id)}?page=$pageIndex&limit=$pageSize"
            execute(url, { emptyList() }) { parseLibraryResponse(it) }
        }
    }

    private fun parseLibraryResponse(body: String): List<Track> {
        try {
            val response: dev.brahmkshatriya.echo.extension.models.DabLibraryResponse = json.decodeFromString(body)
            response.library?.tracks?.let { tracks ->
                return tracks.mapNotNull { track ->
                    runCatching { converter.toTrack(track) }.getOrNull()
                }
            }
        } catch (_: Throwable) {}
        return try {
            val root = json.parseToJsonElement(body)
            jsonParsingUtils.parseTracksFromResponse(root)
        } catch (_: Throwable) { emptyList() }
    }

    /** Always fetch library/playlist list fresh */
    fun fetchLibraryPlaylistsPage(pageIndex: Int = 1, pageSize: Int = 50, includeCovers: Boolean = true): List<Playlist> {
        if (!requestUtils.isLoggedIn()) return emptyList()
        val querySuffix = "?page=$pageIndex&limit=$pageSize"
        var result: List<Playlist> = emptyList()
        for (base in PLAYLIST_LIST_ENDPOINTS) {
            val url = base + querySuffix
            result = execute(url, { emptyList() }) { parsePlaylistsFromBody(it) }
            if (result.isNotEmpty()) break
        }
        if (result.isNotEmpty() && includeCovers) augmentPlaylistsWithCovers(result.toMutableList())
        return result
    }

    private fun parsePlaylistsFromBody(body: String): List<Playlist> {
        try {
            val root = json.parseToJsonElement(body)
            val list = mutableListOf<Playlist>()
            val arr = jsonParsingUtils.findArrayInJson(root, listOf("libraries", "playlists", "data", "items", "results"))
            arr?.forEach { el ->
                if (el is JsonObject) {
                    runCatching {
                        val dp = json.decodeFromJsonElement(dev.brahmkshatriya.echo.extension.models.DabPlaylist.serializer(), el)
                        list.add(converter.toPlaylist(dp))
                    }.getOrElse {
                        val id = extractStringValue(el, "id", "playlistId", "libraryId", "slug")
                        val title = extractStringValue(el, "name", "title", "label") ?: "Playlist"
                        val tc = extractStringValue(el, "trackCount", "count")?.toIntOrNull() ?: 0
                        if (!id.isNullOrBlank()) list.add(
                            Playlist(
                                id = id,
                                title = title,
                                authors = listOf(Artist(id = "user", name = "You")),
                                isEditable = false,
                                trackCount = tc.toLong(),
                                cover = null
                            )
                        )
                    }
                }
            }
            return list
        } catch (_: Throwable) { }
        return emptyList()
    }

    private fun extractStringValue(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val value = (obj[key] as? JsonPrimitive)?.content
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    /** Attempt to enrich playlists with covers (may trigger network calls per playlist) */
    private fun augmentPlaylistsWithCovers(playlists: MutableList<Playlist>) {
        for (i in playlists.indices) {
            val p = playlists[i]
            try {
                val tracks = try { runBlocking { fetchTracksForPlaylistSync(p) } } catch (_: Throwable) { emptyList() }
                val latestCover = tracks.firstOrNull()?.cover
                if (latestCover != null) playlists[i] = p.copy(cover = latestCover)
            } catch (_: Throwable) {}
        }
    }

    /** No-op after cache removal (kept for compatibility) */
    fun invalidateCaches() { /* no-op */ }

    fun createPlaylist(title: String, description: String?): Playlist {
        val bodyJson = buildString {
            append('{'); append("\"name\":\"").append(title.replace("\"", "\\\"")).append('\"')
            if (!description.isNullOrBlank()) append(",\"description\":\"").append(description.replace("\"", "\\\"")).append('\"')
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
                parseSinglePlaylist(body) ?: error("Failed to parse created playlist")
            }
        } catch (e: Throwable) { error("Failed to create playlist: ${e.message}") }
    }

    fun deletePlaylist(playlist: Playlist) {
        val url = "${LIBRARIES_BASE}/${enc(playlist.id)}"
        try {
            httpClient.newCall(requestUtils.newRequestBuilder(url).delete().build()).execute().use { resp ->
                if (!resp.isSuccessful) { requestUtils.clearSessionOnAuthFailure(resp.code); error("Failed to delete playlist: ${resp.code}") }
            }
        } catch (e: Throwable) { error("Failed to delete playlist: ${e.message}") }
    }

    fun editPlaylistMetadata(playlist: Playlist, title: String, description: String?) : Playlist {
        val bodyJson = buildString {
            append('{'); append("\"name\":\"").append(title.replace("\"", "\\\"")).append('\"')
            append(','); append("\"description\":"); if (description == null) append("null") else append("\"").append(description.replace("\"", "\\\"")).append("\"")
            append('}')
        }
        val url = "${LIBRARIES_BASE}/${enc(playlist.id)}"
        return try {
            httpClient.newCall(requestUtils.newRequestBuilder(url).patch(bodyJson.toRequestBody("application/json".toMediaType())).build()).execute().use { resp ->
                if (!resp.isSuccessful) { requestUtils.clearSessionOnAuthFailure(resp.code); error("Failed to edit playlist: ${resp.code}") }
                val body = resp.body?.string(); val updated = body?.let { parseSinglePlaylist(it) }
                updated ?: playlist.copy(title = title)
            }
        } catch (_: Throwable) { playlist.copy(title = title) }
    }

    fun addTracks(playlist: Playlist, new: List<Track>) {
        if (new.isEmpty()) return
        val url = "${LIBRARIES_BASE}/${enc(playlist.id)}$TRACKS_SEGMENT"
        new.forEach { t ->
            try {
                val inner = converter.toDabTrackJson(t).toString()
                val wrapper = "{\"track\":$inner}"
                httpClient.newCall(requestUtils.newRequestBuilder(url).post(wrapper.toRequestBody("application/json".toMediaType())).build()).execute().use { resp ->
                    if (!resp.isSuccessful) requestUtils.clearSessionOnAuthFailure(resp.code)
                }
            } catch (_: Throwable) { }
        }
    }

    fun removeTracks(playlist: Playlist, tracks: List<Track>, indexes: List<Int>) {
        if (indexes.isEmpty()) return
        val uniqueIds = indexes.mapNotNull { tracks.getOrNull(it)?.id }.distinct()
        uniqueIds.forEach { id ->
            val url = "${LIBRARIES_BASE}/${enc(playlist.id)}$TRACKS_SEGMENT/${enc(id)}"
            try {
                httpClient.newCall(requestUtils.newRequestBuilder(url).delete().build()).execute().use { resp ->
                    if (!resp.isSuccessful) requestUtils.clearSessionOnAuthFailure(resp.code)
                }
            } catch (_: Throwable) { }
        }
    }

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
