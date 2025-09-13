package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.models.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import android.util.Log
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.extension.dabapi.PlaylistService
import dev.brahmkshatriya.echo.extension.dabapi.FavoritesService
import dev.brahmkshatriya.echo.extension.dabapi.StreamResolver

class DABApi(
    private val httpClient: OkHttpClient,
    private val converter: Converter,
    private val settings: Settings
) {

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val streamResolver: StreamResolver by lazy { StreamResolver(httpClient, json, settings) }
    private val playlistService: PlaylistService by lazy { PlaylistService(httpClient, json, converter, settings) }
    private val favoritesService: FavoritesService by lazy { FavoritesService(httpClient, json, settings, converter) }

    // Session validation cache
    private var lastSessionCheck: Long = 0
    private var isSessionValid: Boolean = false
    private val SESSION_CHECK_INTERVAL = 5 * 60 * 1000L // 5 minutes

    init {
        try { streamResolver.loadPersistentStreamCache() } catch (_: Throwable) { /* best-effort */ }
    }

    fun shutdown() {
        try { ioScope.cancel() } catch (_: Throwable) { }
    }

    private fun cookieHeaderValue(): String? {
        val raw = settings.getString("session_cookie") ?: return null
        // Ensure we always return a single name=value cookie pair (strip attributes)
        val firstPart = raw.split(';').firstOrNull()?.trim() ?: return null
        if (firstPart.isEmpty()) return null
        return if (firstPart.contains('=')) firstPart else "session=$firstPart"
    }

    // Check if we have a valid session cookie
    fun hasValidSession(): Boolean {
        val cookie = cookieHeaderValue()
        if (cookie.isNullOrBlank()) return false

        val now = System.currentTimeMillis()
        if (now - lastSessionCheck < SESSION_CHECK_INTERVAL && isSessionValid) {
            return true
        }

        // Validate session by calling /auth/me
        try {
            val response: DabUserResponse = executeAuthenticated("https://dab.yeet.su/api/auth/me")
            isSessionValid = response.user != null
            lastSessionCheck = now

            if (!isSessionValid) {
                // Clear invalid session
                clearSession()
            }

            return isSessionValid
        } catch (_: Throwable) {
            isSessionValid = false
            clearSession()
            return false
        }
    }

    // Clear session data
    fun clearSession() {
        settings.putString("session_cookie", null)
        isSessionValid = false
        lastSessionCheck = 0
    }

    // Helper to create a Request.Builder with standard headers and optional cookie
    private fun newRequestBuilder(url: String, includeCookie: Boolean = true): Request.Builder {
        val rb = Request.Builder().url(url)
        if (includeCookie) {
            val cookie = cookieHeaderValue()
            if (!cookie.isNullOrBlank()) rb.header("Cookie", cookie)
        }
        rb.header("Accept", "application/json").header("User-Agent", "EchoDAB-Extension/1.0")
        return rb
    }

    fun loginAndSaveCookie(email: String, pass: String): User {
        val loginRequest = DabLoginRequest(email, pass)
        val requestBody = json.encodeToString(DabLoginRequest.serializer(), loginRequest)
            .toRequestBody("application/json".toMediaType())

        val req = newRequestBuilder("https://dab.yeet.su/api/auth/login", includeCookie = false).post(requestBody).build()
        httpClient.newCall(req).execute().use { response ->
            val cookieHeader = response.headers["Set-Cookie"]
            if (cookieHeader != null) {
                val sessionCookie = cookieHeader.split(';').firstOrNull { it.trim().startsWith("session=") }
                if (sessionCookie != null) {
                    settings.putString("session_cookie", sessionCookie)
                    // Reset session validation cache
                    isSessionValid = true
                    lastSessionCheck = System.currentTimeMillis()
                }
            }

            val body = response.body?.string() ?: error("Empty response body")
            if (!response.isSuccessful) {
                error("API Error: ${response.code} ${response.message} - $body")
            }

            val userResponse: DabUserResponse = json.decodeFromString(body)
            return converter.toUser(userResponse.user ?: error("User data is null after login"))
        }
    }

    private inline fun <reified T> executeAuthenticated(url: String): T {
        val req = newRequestBuilder(url).build()
        httpClient.newCall(req).execute().use { response ->
            val body = response.body?.string() ?: error("Empty response body")
            if (!response.isSuccessful) {
                // If unauthorized, clear session
                if (response.code == 401 || response.code == 403) {
                    clearSession()
                }
                error("API Error: ${response.code} ${response.message} - $body")
            }
            return json.decodeFromString(body)
        }
    }

    fun getMe(): User {
        if (!hasValidSession()) {
            error("No valid session")
        }
        val response: DabUserResponse = executeAuthenticated("https://dab.yeet.su/api/auth/me")
        return converter.toUser(response.user ?: error("User data is null"))
    }

    // Stream resolution delegates
    fun getCachedStreamUrl(trackId: String?): String? = streamResolver.getCachedStreamUrl(trackId)
    fun resolveApiStreamEndpoint(endpointUrl: String): String? = streamResolver.resolveApiStreamEndpoint(endpointUrl)
    fun quickResolveStreamUrl(trackId: String, timeoutMs: Long = 1000L): String? = streamResolver.quickResolveStreamUrl(trackId, timeoutMs)
    fun getStreamUrl(trackId: String): String? = streamResolver.getStreamUrl(trackId)

    // Favorites delegates
    fun getFavorites(limit: Int = 200, offset: Int = 0): List<Track> = favoritesService.getFavorites(limit, offset)
    fun getFavoritesAuthenticated(limit: Int = 200, offset: Int = 0): List<Track> = favoritesService.getFavoritesAuthenticated(limit, offset)
    fun fetchFavoritesPage(pageIndex: Int = 1, pageSize: Int = 200): List<Track> = favoritesService.fetchFavoritesPage(pageIndex, pageSize)

    fun getAlbum(albumId: String): DabAlbum? {
         return try {
            val url = "https://dab.yeet.su/api/album?id=${URLEncoder.encode(albumId, "UTF-8")}"
            val req = newRequestBuilder(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                try {
                    val sr: DabSingleAlbumResponse = json.decodeFromString(body)
                    return sr.album
                } catch (_: Throwable) { null }
            }
        } catch (_: Throwable) { null }
    }

    fun getLyrics(artist: String, title: String): String? {
        return try {
            val url = "https://dab.yeet.su/api/lyrics?artist=${URLEncoder.encode(artist, "UTF-8")}&title=${URLEncoder.encode(title, "UTF-8")}"
            val req = newRequestBuilder(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                try {
                    val r: DabLyricsResponse = json.decodeFromString(body)
                    return r.lyrics
                } catch (_: Throwable) { body }
            }
        } catch (_: Throwable) { null }
    }

    fun addFavorite(track: Track): Boolean {
          val url = "https://dab.yeet.su/api/favorites"
         try {
             val bodyJson = converter.toDabTrackJson(track).toString()
             val req1 = newRequestBuilder(url).post(bodyJson.toRequestBody("application/json".toMediaType())).build()
             httpClient.newCall(req1).execute().use { resp ->
                 if (resp.isSuccessful) {
                     try { ioScope.launch { favoritesService.fetchFavoritesPage(1, 50) } } catch (_: Throwable) { }
                     return true
                 }
             }
         } catch (_: Throwable) { }

         try {
             val inner = converter.toDabTrackJson(track)
             val wrapperJson = buildString {
                 append('{')
                 append("\"track\":")
                 append(inner.toString())
                 append('}')
             }
             val req2 = newRequestBuilder(url).post(wrapperJson.toRequestBody("application/json".toMediaType())).build()
             httpClient.newCall(req2).execute().use { resp ->
                 if (resp.isSuccessful) {
                     try { ioScope.launch { favoritesService.fetchFavoritesPage(1, 50) } } catch (_: Throwable) { }
                 }
                 return resp.isSuccessful
             }
         } catch (_: Throwable) { }
         return false
     }

    fun removeFavorite(trackId: String): Boolean {
         return try {
             val url = "https://dab.yeet.su/api/favorites?trackId=${URLEncoder.encode(trackId, "UTF-8")}"
             val req = newRequestBuilder(url).delete().build()
             httpClient.newCall(req).execute().use { resp ->
                val ok = resp.isSuccessful
                if (ok) {
                    try { ioScope.launch { favoritesService.fetchFavoritesPage(1, 50) } } catch (_: Throwable) {}
                }
                ok
             }
         } catch (_: Throwable) { false }
     }

    fun isTrackFavorite(trackId: String): Boolean {
        return try {
            val list = fetchFavoritesPage(1, 500)
            list.any { it.id == trackId || it.extras["dab_id"] == trackId }
        } catch (_: Throwable) { false }
    }

    // Playlist delegates
    fun fetchLibraryPlaylistsPage(pageIndex: Int = 1, pageSize: Int = 50): List<Playlist> = playlistService.fetchLibraryPlaylistsPage(pageIndex, pageSize)

    fun getPlaylistTracks(playlist: Playlist, pageIndex: Int = 1, pageSize: Int = 1000): PagedData<Track> {
         return PagedData.Single {
            try {
                kotlinx.coroutines.runBlocking {
                    fetchAllPlaylistTracks(playlist, pageSize)
                }
            } catch (_: Throwable) { emptyList() }
        }
     }

    suspend fun fetchAllPlaylistTracks(playlist: Playlist, pageSize: Int = 1000): List<Track> {
        return try {
            Log.d("DABApi", "fetchAllPlaylistTracks called for playlist=${playlist.id}, title='${playlist.title}'")

            val fromService = playlistService.fetchTracksForPlaylistSync(playlist, 1, pageSize)
            Log.d("DABApi", "PlaylistService returned ${fromService.size} tracks for playlist=${playlist.id}")

            if (fromService.isNotEmpty()) {
                Log.i("DABApi", "SUCCESS: Using ${fromService.size} tracks from PlaylistService for playlist=${playlist.id}")
                return fromService
            }

            Log.d("DABApi", "PlaylistService returned empty, trying fallback API endpoints for playlist=${playlist.id}")

            withContext(Dispatchers.IO) {
                val out = mutableListOf<Track>()
                val candidateBasePaths = listOf(
                    "https://dab.yeet.su/api/libraries/${URLEncoder.encode(playlist.id, "UTF-8")}/tracks",
                    "https://dab.yeet.su/api/playlists/${URLEncoder.encode(playlist.id, "UTF-8")}/tracks",
                    "https://dab.yeet.su/api/libraries/${URLEncoder.encode(playlist.id, "UTF-8")}",
                    "https://dab.yeet.su/api/playlists/${URLEncoder.encode(playlist.id, "UTF-8")}",
                    "https://dab.yeet.su/api/libraries/${URLEncoder.encode(playlist.id, "UTF-8")}/items",
                    // Additional fallbacks
                    "https://dab.yeet.su/api/library/${URLEncoder.encode(playlist.id, "UTF-8")}/tracks",
                    "https://dab.yeet.su/api/playlist/${URLEncoder.encode(playlist.id, "UTF-8")}/tracks"
                )

                for ((index, base) in candidateBasePaths.withIndex()) {
                    try {
                        Log.d("DABApi", "Trying fallback endpoint ${index + 1}/${candidateBasePaths.size}: $base")

                        var page = 1
                        var totalFetched = 0
                        var consecutiveEmptyPages = 0
                        val maxEmptyPages = 2

                        while (true) {
                            val url = "$base?page=$page&limit=$pageSize"
                            val rb = Request.Builder().url(url)
                            val cookie = cookieHeaderValue()
                            if (!cookie.isNullOrBlank()) rb.header("Cookie", cookie)
                            rb.header("Accept", "application/json").header("User-Agent", "EchoDAB-Extension/1.0")

                            httpClient.newCall(rb.build()).execute().use { resp ->
                                Log.d("DABApi", "Fallback response: ${resp.code} ${resp.message} for $url")

                                if (!resp.isSuccessful) {
                                    Log.d("DABApi", "Fallback request failed with ${resp.code}, breaking")
                                    break
                                }

                                val body = resp.body?.string()
                                if (body.isNullOrEmpty()) {
                                    Log.d("DABApi", "Empty response body, breaking")
                                    break
                                }

                                Log.d("DABApi", "Response body length: ${body.length} chars")

                                try {
                                    val root = json.parseToJsonElement(body)
                                    val parsed = parseTracksFromResponse(root)
                                    Log.d("DABApi", "Parsed ${parsed.size} tracks from fallback response")

                                    if (parsed.isEmpty()) {
                                        consecutiveEmptyPages++
                                        if (consecutiveEmptyPages >= maxEmptyPages) {
                                            Log.d("DABApi", "Too many consecutive empty pages, breaking")
                                            break
                                        }
                                    } else {
                                        consecutiveEmptyPages = 0
                                        out.addAll(parsed)
                                        totalFetched += parsed.size
                                    }

                                    if (parsed.size < pageSize) {
                                        Log.d("DABApi", "Received fewer tracks than requested, assuming last page")
                                        break
                                    } else {
                                        page++
                                    }
                                } catch (e: Throwable) {
                                    Log.w("DABApi", "Exception parsing response from $url: ${e.message}")
                                    break
                                }
                            }
                        }

                        if (totalFetched > 0) {
                            Log.i("DABApi", "SUCCESS: Fetched $totalFetched total tracks for playlist ${playlist.id} from fallback $base")
                            return@withContext out
                        } else {
                            Log.d("DABApi", "No tracks found from fallback endpoint $base")
                        }
                    } catch (e: Throwable) {
                        Log.w("DABApi", "Exception trying fallback endpoint $base: ${e.message}")
                    }
                }

                Log.w("DABApi", "FAILED: No tracks found for playlist=${playlist.id} from any fallback endpoint")
                out
            }
        } catch (e: Throwable) {
            Log.e("DABApi", "Exception in fetchAllPlaylistTracks for playlist=${playlist.id}: ${e.message}", e)
            emptyList()
        }
    }

    // Consolidated manual track parsing (removing redundancy across services)
    private fun tryParseManualTrack(el: JsonObject): DabTrack? {
        return try {
            val idEl = el["id"] ?: el["trackId"] ?: el["track_id"] ?: el["track"] ?: return null
            val idStr = (idEl as? JsonPrimitive)?.content ?: idEl.toString()
            val idInt = idStr.toIntOrNull() ?: return null

            val title = (el["title"] as? JsonPrimitive)?.content
                ?: (el["name"] as? JsonPrimitive)?.content ?: ""
            val artist = (el["artist"] as? JsonPrimitive)?.content
                ?: (el["artistName"] as? JsonPrimitive)?.content ?: ""
            val artistId = (el["artistId"] as? JsonPrimitive)?.content?.toIntOrNull()
            val albumTitle = (el["albumTitle"] as? JsonPrimitive)?.content
                ?: (el["album"] as? JsonPrimitive)?.content
            val albumCover = (el["albumCover"] as? JsonPrimitive)?.content
            val albumId = (el["albumId"] as? JsonPrimitive)?.content
            val releaseDate = (el["releaseDate"] as? JsonPrimitive)?.content
            val genre = (el["genre"] as? JsonPrimitive)?.content
            val duration = (el["duration"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

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

    private fun parseTracksFromResponse(root: JsonElement): List<Track> {
        val parsed = mutableListOf<Track>()

        when (root) {
            is JsonObject -> {
                val candidates = listOf("tracks", "data", "items", "results", "favorites", "library", "content")
                for (key in candidates) {
                    val arr = root[key] as? JsonArray
                    if (arr != null) {
                        for (el in arr) if (el is JsonObject) try {
                            val dt = json.decodeFromJsonElement(DabTrack.serializer(), el)
                            parsed.add(converter.toTrack(dt))
                        } catch (_: Throwable) {
                            // Try enhanced manual parsing as fallback
                            val track = tryParseManualTrack(el)
                            if (track != null) parsed.add(converter.toTrack(track))
                        }
                        if (parsed.isNotEmpty()) break
                    }
                }
            }
            is JsonArray -> {
                for (el in root) if (el is JsonObject) try {
                    val dt = json.decodeFromJsonElement(DabTrack.serializer(), el)
                    parsed.add(converter.toTrack(dt))
                } catch (_: Throwable) {
                    // Try enhanced manual parsing as fallback
                    val track = tryParseManualTrack(el)
                    if (track != null) parsed.add(converter.toTrack(track))
                }
            }
            else -> { /* other types */ }
        }
        return parsed
    }

    fun getArtistDetails(artistId: String): DabArtist? {
        return try {
            val url = "https://dab.yeet.su/api/artist?id=${URLEncoder.encode(artistId, "UTF-8")}"
            val req = newRequestBuilder(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                try {
                    json.decodeFromString<DabArtist>(body)
                } catch (_: Throwable) { null }
            }
        } catch (_: Throwable) { null }
    }

    fun getArtistDiscography(artistId: String): List<DabAlbum> {
        return try {
            val url = "https://dab.yeet.su/api/discography?artistId=${URLEncoder.encode(artistId, "UTF-8")}"
            val req = newRequestBuilder(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                try {
                    val root = json.decodeFromString<JsonElement>(body)
                    val out = mutableListOf<DabAlbum>()

                    val arr = when (root) {
                        is JsonObject -> (root["albums"] as? JsonArray) ?: (root["data"] as? JsonArray) ?: (root["items"] as? JsonArray)
                        is JsonArray -> root
                        else -> null
                    }

                    arr?.forEach { el ->
                        if (el is JsonObject) {
                            try {
                                val album = json.decodeFromJsonElement(DabAlbum.serializer(), el)
                                out.add(album)
                            } catch (_: Throwable) { }
                        }
                    }
                    out
                } catch (_: Throwable) { emptyList() }
            }
        } catch (_: Throwable) { emptyList() }
    }

    // Add a debug helper method to diagnose playlist issues
    suspend fun debugPlaylistIssues(playlist: Playlist): String {
        val debug = StringBuilder()
        debug.appendLine("=== PLAYLIST DEBUG INFO ===")
        debug.appendLine("Playlist ID: ${playlist.id}")
        debug.appendLine("Playlist Title: ${playlist.title}")
        debug.appendLine("Track Count: ${playlist.trackCount}")

        // Check session validity
        val hasSession = hasValidSession()
        debug.appendLine("Has Valid Session: $hasSession")

        if (!hasSession) {
            debug.appendLine("ERROR: No valid session - user needs to login")
            return debug.toString()
        }

        // Check cookie
        val cookie = cookieHeaderValue()
        debug.appendLine("Session Cookie Present: ${!cookie.isNullOrBlank()}")

        // Try each endpoint manually
        val endpoints = listOf(
            "https://dab.yeet.su/api/libraries/${URLEncoder.encode(playlist.id, "UTF-8")}/tracks",
            "https://dab.yeet.su/api/playlists/${URLEncoder.encode(playlist.id, "UTF-8")}/tracks",
            "https://dab.yeet.su/api/libraries/${URLEncoder.encode(playlist.id, "UTF-8")}",
            "https://dab.yeet.su/api/playlists/${URLEncoder.encode(playlist.id, "UTF-8")}"
        )

        for ((index, url) in endpoints.withIndex()) {
            try {
                val rb = Request.Builder().url(url)
                if (!cookie.isNullOrBlank()) rb.header("Cookie", cookie)
                rb.header("Accept", "application/json").header("User-Agent", "EchoDAB-Extension/1.0")

                httpClient.newCall(rb.build()).execute().use { resp ->
                    debug.appendLine("Endpoint ${index + 1}: $url")
                    debug.appendLine("  Response: ${resp.code} ${resp.message}")

                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        debug.appendLine("  Body Length: ${body?.length ?: 0} chars")

                        if (!body.isNullOrEmpty()) {
                            try {
                                val root = json.parseToJsonElement(body)
                                val tracks = parseTracksFromResponse(root)
                                debug.appendLine("  Parsed Tracks: ${tracks.size}")
                                if (tracks.isNotEmpty()) {
                                    debug.appendLine("  FOUND TRACKS - This endpoint works!")
                                    debug.appendLine("  Sample track: ${tracks.first().title} by ${tracks.first().artists.firstOrNull()?.name}")
                                }
                            } catch (e: Throwable) {
                                debug.appendLine("  Parse Error: ${e.message}")
                                debug.appendLine("  Body Preview: ${body.take(200)}")
                            }
                        }
                    } else {
                        debug.appendLine("  ERROR: Request failed")
                    }
                }
            } catch (e: Throwable) {
                debug.appendLine("Endpoint ${index + 1}: EXCEPTION - ${e.message}")
            }
        }

        return debug.toString()
    }
}
