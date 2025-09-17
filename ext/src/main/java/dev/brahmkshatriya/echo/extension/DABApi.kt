package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.models.*
import dev.brahmkshatriya.echo.extension.utils.JsonParsingUtils
import dev.brahmkshatriya.echo.extension.utils.RequestUtils
import dev.brahmkshatriya.echo.extension.utils.ApiConstants
import kotlinx.serialization.json.Json
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import dev.brahmkshatriya.echo.extension.dabapi.*

class DABApi(
    private val httpClient: OkHttpClient,
    private val converter: Converter,
    private val settings: Settings
) {
    companion object { private const val SESSION_CHECK_INTERVAL_MS = 5 * 60 * 1000L }

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val requestUtils by lazy { RequestUtils(settings) }
    private val jsonParsingUtils by lazy { JsonParsingUtils(json, converter) }
    private val streamResolver by lazy { StreamResolver(httpClient, json, settings) }
    private val playlistService by lazy { PlaylistService(httpClient, json, converter, settings) }
    private val favoritesService by lazy { FavoritesService(httpClient, json, settings, converter) }
    private var lastSessionCheck: Long = 0
    private var isSessionValid: Boolean = false

    // ---------------- Session & Auth ----------------
    fun hasValidSession(): Boolean {
        if (requestUtils.getCookieHeaderValue().isNullOrBlank()) return false
        val now = System.currentTimeMillis()
        if (now - lastSessionCheck < SESSION_CHECK_INTERVAL_MS && isSessionValid) return true
        return try {
            val response: DabUserResponse = executeAuthenticated(ApiConstants.api("auth/me"))
            isSessionValid = response.user != null
            lastSessionCheck = now
            if (!isSessionValid) clearSession()
            isSessionValid
        } catch (_: Throwable) { isSessionValid = false; clearSession(); false }
    }

    fun clearSession() { settings.putString("session_cookie", null); isSessionValid = false; lastSessionCheck = 0 }

    private fun newRequestBuilder(url: String, includeCookie: Boolean = true): Request.Builder =
        requestUtils.newRequestBuilder(url, includeCookie)

    fun loginAndSaveCookie(email: String, pass: String): User {
        val body = json.encodeToString(DabLoginRequest.serializer(), DabLoginRequest(email, pass))
            .toRequestBody("application/json".toMediaType())
        val req = newRequestBuilder(ApiConstants.api("auth/login"), includeCookie = false).post(body).build()
        httpClient.newCall(req).execute().use { resp ->
            resp.headers["Set-Cookie"]?.let { raw ->
                raw.split(';').firstOrNull { it.trim().startsWith("session=") }?.let {
                    settings.putString("session_cookie", it)
                    isSessionValid = true; lastSessionCheck = System.currentTimeMillis()
                }
            }
            val respBody = resp.body?.string() ?: error("Empty response body")
            if (!resp.isSuccessful) error("API Error: ${resp.code} ${resp.message} - $respBody")
            val u: DabUserResponse = json.decodeFromString(respBody)
            return converter.toUser(u.user ?: error("User data is null after login"))
        }
    }

    private inline fun <reified T> executeAuthenticated(url: String): T {
        val req = newRequestBuilder(url).build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: error("Empty response body")
            if (!resp.isSuccessful) {
                if (resp.code == 401 || resp.code == 403) clearSession()
                error("API Error: ${resp.code} ${resp.message} - $body")
            }
            return json.decodeFromString(body)
        }
    }

    fun getMe(): User {
        if (!hasValidSession()) error("No valid session")
        val r: DabUserResponse = executeAuthenticated(ApiConstants.api("auth/me"))
        return converter.toUser(r.user ?: error("User data is null"))
    }

    // ---------------- Stream & Favorites ----------------
    fun getStreamUrl(trackId: String): String? = streamResolver.getStreamUrl(trackId)
    fun getFavoritesAuthenticated(limit: Int = 200, offset: Int = 0): List<Track> = favoritesService.getFavorites(limit, offset)

    fun addFavorite(track: Track): Boolean {
        val url = ApiConstants.api("favorites")
        return try {
            val direct = converter.toDabTrackJson(track).toString()
            val req1 = newRequestBuilder(url).post(direct.toRequestBody("application/json".toMediaType())).build()
            httpClient.newCall(req1).execute().use { if (it.isSuccessful) return true }
            val wrapper = "{\"track\":${converter.toDabTrackJson(track)}}"
            val req2 = newRequestBuilder(url).post(wrapper.toRequestBody("application/json".toMediaType())).build()
            httpClient.newCall(req2).execute().use { it.isSuccessful }
        } catch (_: Throwable) { false }
    }

    fun removeFavorite(trackId: String): Boolean = try {
        val url = ApiConstants.api("favorites?trackId=$trackId")
        httpClient.newCall(newRequestBuilder(url).delete().build()).execute().use { it.isSuccessful }
    } catch (_: Throwable) { false }

    fun isTrackFavorite(trackId: String): Boolean = runCatching {
        getFavoritesAuthenticated().any { it.id == trackId || it.extras["dab_id"] == trackId }
    }.getOrDefault(false)

    // ---------------- Albums ----------------
    fun getAlbum(albumId: String): DabAlbum? = firstSuccessful(
        listOf(
            ApiConstants.api("album/$albumId"),
            ApiConstants.api("album?albumId=$albumId"),
            ApiConstants.api("album?id=$albumId")
        )
    ) { url ->
        val body = getBody(url) ?: return@firstSuccessful null
        runCatching { json.decodeFromString<DabSingleAlbumResponse>(body).album }.getOrNull()
            ?: runCatching { json.decodeFromString<DabAlbum>(body) }.getOrNull()
            ?: runCatching { jsonParsingUtils.parseAlbum(json.parseToJsonElement(body)) }.getOrNull()
    }

    fun getLyrics(artist: String, title: String): String? = try {
        val url = ApiConstants.api("lyrics?artist=$artist&title=$title")
        httpClient.newCall(newRequestBuilder(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            runCatching { json.decodeFromString<DabLyricsResponse>(body).lyrics }.getOrNull() ?: body
        }
    } catch (_: Throwable) { null }

    // ---------------- Artists & Discography ----------------
    fun getArtistDetails(artistId: String): DabArtist? = firstSuccessful(
        listOf(
            ApiConstants.api("artist/$artistId"),
            ApiConstants.api("artist?artistId=$artistId"),
            ApiConstants.api("artist?id=$artistId")
        )
    ) { url ->
        val body = getBody(url) ?: return@firstSuccessful null
        runCatching { json.decodeFromString<DabArtist>(body) }.getOrNull()
            ?: runCatching { jsonParsingUtils.parseArtist(json.parseToJsonElement(body)) }.getOrNull()
    }

    fun getArtistDiscography(artistId: String): List<DabAlbum> = firstSuccessful(
        listOf(
            ApiConstants.api("discography?artistId=$artistId"),
            ApiConstants.api("artist/$artistId/albums"),
            ApiConstants.api("artist/$artistId/discography"),
            ApiConstants.api("albums?artistId=$artistId"),
            ApiConstants.api("discography/$artistId")
        )
    ) { url ->
        val body = getBody(url) ?: return@firstSuccessful null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return@firstSuccessful null
        jsonParsingUtils.parseDiscography(root).ifEmpty { null }
    } ?: emptyList()

    // ---------------- Playlists ----------------
    fun fetchLibraryPlaylistsPage(pageIndex: Int = 1, pageSize: Int = 50): List<Playlist> =
        playlistService.fetchLibraryPlaylistsPage(pageIndex, pageSize)
    fun createPlaylist(title: String, description: String?): Playlist = playlistService.createPlaylist(title, description)
    fun deletePlaylist(playlist: Playlist) = playlistService.deletePlaylist(playlist)
    fun editPlaylistMetadata(playlist: Playlist, title: String, description: String?): Playlist =
        playlistService.editPlaylistMetadata(playlist, title, description)
    fun addTracksToPlaylist(playlist: Playlist, new: List<Track>) = playlistService.addTracks(playlist, new)
    fun removeTracksFromPlaylist(playlist: Playlist, tracks: List<Track>, indexes: List<Int>) =
        playlistService.removeTracks(playlist, tracks, indexes)

    fun getPlaylistTracks(playlist: Playlist, pageSize: Int = 1000): PagedData<Track> = PagedData.Single {
        runCatching { runBlocking { fetchAllPlaylistTracks(playlist, pageSize) } }.getOrElse { emptyList() }
    }

    suspend fun fetchAllPlaylistTracks(playlist: Playlist, pageSize: Int = 1000): List<Track> {
        return try {
            val endpoints = listOf(
                ApiConstants.api("libraries/${playlist.id}?page=1&limit=$pageSize"),
                ApiConstants.api("libraries/${playlist.id}/tracks?page=1&limit=$pageSize"),
                ApiConstants.api("playlists/${playlist.id}?page=1&limit=$pageSize"),
                ApiConstants.api("playlists/${playlist.id}/tracks?page=1&limit=$pageSize")
            )
            playlistService.fetchTracksForPlaylistSync(playlist, 1, pageSize).ifEmpty {
                withContext(Dispatchers.IO) {
                    for (url in endpoints) {
                        try {
                            httpClient.newCall(newRequestBuilder(url).build()).execute().use { resp ->
                                if (!resp.isSuccessful) return@use
                                val body = resp.body?.string() ?: return@use
                                val libTracks = runCatching { json.decodeFromString<DabLibraryResponse>(body).library?.tracks }
                                    .getOrNull()?.mapNotNull { runCatching { converter.toTrack(it) }.getOrNull() } ?: emptyList()
                                if (libTracks.isNotEmpty()) return@withContext libTracks
                                val fallback = jsonParsingUtils.parseTracksFromResponse(json.parseToJsonElement(body))
                                if (fallback.isNotEmpty()) return@withContext fallback
                            }
                        } catch (_: Throwable) { }
                    }
                    emptyList()
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // ---------------- Search ----------------
    suspend fun searchAll(query: String, limit: Int = 20): Triple<List<Track>, List<Album>, List<Artist>> {
        if (query.isBlank()) return Triple(emptyList(), emptyList(), emptyList())
        return withContext(Dispatchers.IO) {
            val unified = runCatching {
                val url = ApiConstants.api("search?q=$query&limit=$limit")
                httpClient.newCall(newRequestBuilder(url, includeCookie = false).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            }.getOrNull()
            if (!unified.isNullOrBlank()) {
                jsonParsingUtils.parseUnifiedSearch(unified)?.let { return@withContext it }
            }
            val tracks = searchByTypeInternal(query, "track", limit).filterIsInstance<Track>()
            val albums = searchByTypeInternal(query, "album", limit).filterIsInstance<Album>()
            val artists = searchByTypeInternal(query, "artist", limit).filterIsInstance<Artist>()
            Triple(tracks, albums, artists)
        }
    }

    private fun searchByTypeInternal(query: String, type: String, limit: Int): List<Any> = try {
        val url = ApiConstants.api("search?q=$query&type=$type&limit=$limit")
        httpClient.newCall(newRequestBuilder(url, includeCookie = false).build()).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val parsed = jsonParsingUtils.parseSearchResults(body, type)
            if (parsed.isNotEmpty()) return parsed
            if (type == "artist") jsonParsingUtils.extractArtistsFromTrackSearch(body) else emptyList()
        }
    } catch (_: Throwable) { emptyList() }

    suspend fun searchTracks(query: String, limit: Int = 20): List<Track> =
        withContext(Dispatchers.IO) { searchByTypeInternal(query, "track", limit).filterIsInstance<Track>() }
    suspend fun searchAlbums(query: String, limit: Int = 20): List<Album> =
        withContext(Dispatchers.IO) { searchByTypeInternal(query, "album", limit).filterIsInstance<Album>() }
    suspend fun searchArtists(query: String, limit: Int = 20): List<Artist> =
        withContext(Dispatchers.IO) { searchByTypeInternal(query, "artist", limit).filterIsInstance<Artist>() }

    // ---------------- Helpers ----------------
    private inline fun <T> firstSuccessful(endpoints: List<String>, fetch: (String) -> T?): T? {
        for (u in endpoints) {
            val r = runCatching { fetch(u) }.getOrNull(); if (r != null) return r
        }
        return null
    }

    private fun getBody(url: String, includeCookie: Boolean = true): String? = try {
        httpClient.newCall(newRequestBuilder(url, includeCookie).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (_: Throwable) { null }

    /**
     * Gracefully release any underlying HTTP resources. Safe to call multiple times.
     * Provided to allow extension to explicitly free resources on logout/unload.
     */
    fun shutdown() {
        try { httpClient.connectionPool.evictAll() } catch (_: Throwable) {}
        try { httpClient.dispatcher.executorService.shutdown() } catch (_: Throwable) {}
        // No further action; caller owns cache/lifecycle if any
    }
}
