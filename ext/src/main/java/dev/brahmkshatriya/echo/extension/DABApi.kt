package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.models.*
import dev.brahmkshatriya.echo.extension.utils.JsonParsingUtils
import dev.brahmkshatriya.echo.extension.utils.RequestUtils
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
import kotlinx.coroutines.async

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

    // Consolidated utilities
    private val requestUtils: RequestUtils by lazy { RequestUtils(settings) }
    private val jsonParsingUtils: JsonParsingUtils by lazy { JsonParsingUtils(json, converter) }

    // Session validation cache
    private var lastSessionCheck: Long = 0
    private var isSessionValid: Boolean = false
    private val SESSION_CHECK_INTERVAL = 5 * 60 * 1000L

    init {
        try { streamResolver.loadPersistentStreamCache() } catch (_: Throwable) { /* best-effort */ }
    }

    /** Clean up resources */
    fun shutdown() {
        try { ioScope.cancel() } catch (_: Throwable) { }
    }

    /** Extract session cookie from settings */
    private fun cookieHeaderValue(): String? = requestUtils.getCookieHeaderValue()

    /** Check if user has valid session by validating with API */
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

    /** Clear stored session data */
    fun clearSession() {
        settings.putString("session_cookie", null)
        isSessionValid = false
        lastSessionCheck = 0
    }

    /** Create HTTP request builder with standard headers */
    private fun newRequestBuilder(url: String, includeCookie: Boolean = true): Request.Builder =
        requestUtils.newRequestBuilder(url, includeCookie)

    /** Login user and store session cookie */
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

    /** Execute authenticated API request */
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

    /** Get current user info */
    fun getMe(): User {
        if (!hasValidSession()) {
            error("No valid session")
        }
        val response: DabUserResponse = executeAuthenticated("https://dab.yeet.su/api/auth/me")
        return converter.toUser(response.user ?: error("User data is null"))
    }

    // Stream resolution delegates
    fun getCachedStreamUrl(trackId: String?): String? = streamResolver.getCachedStreamUrl(trackId)
    fun getStreamUrl(trackId: String): String? = streamResolver.getStreamUrl(trackId)

    // Favorites delegates
    fun getFavoritesAuthenticated(limit: Int = 200, offset: Int = 0): List<Track> = favoritesService.getFavoritesAuthenticated(limit, offset)

    /** Get album details by ID */
    fun getAlbum(albumId: String): DabAlbum? {
        return try {
            Log.d("DABApi", "Fetching album details for albumId: $albumId")

            // Try multiple endpoint formats according to DAB API specification
            val endpoints = listOf(
                "https://dab.yeet.su/api/album/${URLEncoder.encode(albumId, "UTF-8")}", // Path parameter format
                "https://dab.yeet.su/api/album?albumId=${URLEncoder.encode(albumId, "UTF-8")}", // Query parameter format
                "https://dab.yeet.su/api/album?id=${URLEncoder.encode(albumId, "UTF-8")}" // Legacy format
            )

            for ((index, url) in endpoints.withIndex()) {
                try {
                    Log.d("DABApi", "Trying album endpoint ${index + 1}/${endpoints.size}: $url")
                    val req = newRequestBuilder(url).build()
                    httpClient.newCall(req).execute().use { resp ->
                        Log.d("DABApi", "Album endpoint ${index + 1} response: ${resp.code} ${resp.message}")

                        if (!resp.isSuccessful) {
                            if (resp.code == 404) {
                                Log.d("DABApi", "Album not found at endpoint ${index + 1}, trying next")
                                continue
                            } else {
                                Log.w("DABApi", "Album request failed at endpoint ${index + 1}: ${resp.code}")
                                continue
                            }
                        }

                        val body = resp.body?.string() ?: continue
                        Log.d("DABApi", "Album response body length: ${body.length} chars")

                        try {
                            // Try structured response first
                            val sr: DabSingleAlbumResponse = json.decodeFromString(body)
                            Log.i("DABApi", "SUCCESS: Parsed album '${sr.album.title}' with ${sr.album.tracks?.size ?: 0} tracks from endpoint ${index + 1}")
                            return sr.album
                        } catch (e: Throwable) {
                            Log.d("DABApi", "Failed to parse as DabSingleAlbumResponse from endpoint ${index + 1}: ${e.message}")

                            // Try direct album parsing
                            try {
                                val directAlbum: DabAlbum = json.decodeFromString(body)
                                Log.i("DABApi", "SUCCESS: Parsed album '${directAlbum.title}' directly from endpoint ${index + 1}")
                                return directAlbum
                            } catch (e2: Throwable) {
                                Log.d("DABApi", "Failed direct album parsing from endpoint ${index + 1}: ${e2.message}")

                                // Try manual parsing from JSON structure
                                try {
                                    val root = json.parseToJsonElement(body)
                                    val album = parseAlbumFromJsonElement(root)
                                    if (album != null) {
                                        Log.i("DABApi", "SUCCESS: Manually parsed album '${album.title}' from endpoint ${index + 1}")
                                        return album
                                    }
                                } catch (e3: Throwable) {
                                    Log.d("DABApi", "Manual album parsing failed from endpoint ${index + 1}: ${e3.message}")
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.w("DABApi", "Exception calling album endpoint ${index + 1}: ${e.message}")
                }
            }

            Log.w("DABApi", "FAILED: No album found for albumId=$albumId after trying all endpoints")
            null
        } catch (e: Throwable) {
            Log.e("DABApi", "Exception in getAlbum for albumId=$albumId: ${e.message}", e)
            null
        }
    }

    /** Parse album data from JSON element */
    private fun parseAlbumFromJsonElement(root: JsonElement): DabAlbum? {
        return try {
            when (root) {
                is JsonObject -> {
                    // Check if root contains album data directly
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

                    // Parse tracks if available
                    val tracks = mutableListOf<DabTrack>()
                    val tracksArray = albumObj["tracks"] as? JsonArray
                    tracksArray?.forEach { trackEl ->
                        if (trackEl is JsonObject) {
                            try {
                                val track = json.decodeFromJsonElement(DabTrack.serializer(), trackEl)
                                tracks.add(track)
                            } catch (_: Throwable) {
                                val manualTrack = tryParseManualTrack(trackEl)
                                if (manualTrack != null) tracks.add(manualTrack)
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
        } catch (e: Throwable) {
            Log.w("DABApi", "Failed to manually parse album: ${e.message}")
            null
        }
    }

    /** Get song lyrics by artist and title */
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

    /** Add track to favorites */
    fun addFavorite(track: Track): Boolean {
        val url = "https://dab.yeet.su/api/favorites"
        try {
            val bodyJson = converter.toDabTrackJson(track).toString()
            val req1 = newRequestBuilder(url).post(bodyJson.toRequestBody("application/json".toMediaType())).build()
            httpClient.newCall(req1).execute().use { resp ->
                if (resp.isSuccessful) {
                    try { ioScope.launch { favoritesService.invalidateCache(); favoritesService.getFavoritesAuthenticated() } } catch (_: Throwable) { }
                    return true
                }
            }
        } catch (_: Throwable) { }
        try {
            val inner = converter.toDabTrackJson(track)
            val wrapperJson = buildString { append('{'); append("\"track\":"); append(inner.toString()); append('}') }
            val req2 = newRequestBuilder(url).post(wrapperJson.toRequestBody("application/json".toMediaType())).build()
            httpClient.newCall(req2).execute().use { resp ->
                if (resp.isSuccessful) {
                    try { ioScope.launch { favoritesService.invalidateCache(); favoritesService.getFavoritesAuthenticated() } } catch (_: Throwable) { }
                }
                return resp.isSuccessful
            }
        } catch (_: Throwable) { }
        return false
    }

    /** Remove track from favorites */
    fun removeFavorite(trackId: String): Boolean {
        return try {
            val url = "https://dab.yeet.su/api/favorites?trackId=${URLEncoder.encode(trackId, "UTF-8") }"
            val req = newRequestBuilder(url).delete().build()
            httpClient.newCall(req).execute().use { resp ->
                val ok = resp.isSuccessful
                if (ok) {
                    try { ioScope.launch { favoritesService.invalidateCache(); favoritesService.getFavoritesAuthenticated() } } catch (_: Throwable) {}
                }
                ok
            }
        } catch (_: Throwable) { false }
    }

    /** Check if track is in favorites */
    fun isTrackFavorite(trackId: String): Boolean {
        return try {
            val list = getFavoritesAuthenticated()
            list.any { it.id == trackId || it.extras["dab_id"] == trackId }
        } catch (_: Throwable) { false }
    }

    // Playlist delegates
    fun fetchLibraryPlaylistsPage(pageIndex: Int = 1, pageSize: Int = 50): List<Playlist> = playlistService.fetchLibraryPlaylistsPage(pageIndex, pageSize)

    /** Get playlist tracks as paged data */
    fun getPlaylistTracks(playlist: Playlist, pageSize: Int = 1000): PagedData<Track> {
        return PagedData.Single {
            try {
                kotlinx.coroutines.runBlocking {
                    fetchAllPlaylistTracks(playlist, pageSize)
                }
            } catch (_: Throwable) { emptyList() }
        }
    }

    /** Fetch all tracks for a playlist */
    suspend fun fetchAllPlaylistTracks(playlist: Playlist, pageSize: Int = 1000): List<Track> {
        return try {
            Log.d("DABApi", "fetchAllPlaylistTracks called for playlist=${playlist.id}, title='${playlist.title}'")

            val fromService = playlistService.fetchTracksForPlaylistSync(playlist, 1, pageSize)
            Log.d("DABApi", "PlaylistService returned ${fromService.size} tracks for playlist=${playlist.id}")

            if (fromService.isNotEmpty()) {
                Log.i("DABApi", "SUCCESS: Using ${fromService.size} tracks from PlaylistService for playlist=${playlist.id}")
                return fromService
            }

            Log.d("DABApi", "PlaylistService returned empty, trying multiple API endpoints for playlist=${playlist.id}")

            // Try multiple endpoints based on DAB API specification
            withContext(Dispatchers.IO) {
                val endpoints = listOf(
                    // Primary endpoint according to DAB API spec
                    "https://dab.yeet.su/api/libraries/${URLEncoder.encode(playlist.id, "UTF-8")}?page=1&limit=$pageSize",
                    // Alternative endpoints for different DAB server configurations
                    "https://dab.yeet.su/api/libraries/${URLEncoder.encode(playlist.id, "UTF-8")}/tracks?page=1&limit=$pageSize",
                    "https://dab.yeet.su/api/playlists/${URLEncoder.encode(playlist.id, "UTF-8")}?page=1&limit=$pageSize",
                    "https://dab.yeet.su/api/playlists/${URLEncoder.encode(playlist.id, "UTF-8")}/tracks?page=1&limit=$pageSize"
                )

                for ((index, url) in endpoints.withIndex()) {
                    try {
                        Log.d("DABApi", "Trying endpoint ${index + 1}/${endpoints.size}: $url")

                        val rb = Request.Builder().url(url)
                        val cookie = cookieHeaderValue()
                        if (!cookie.isNullOrBlank()) rb.header("Cookie", cookie)
                        rb.header("Accept", "application/json").header("User-Agent", "EchoDAB-Extension/1.0")

                        httpClient.newCall(rb.build()).execute().use { resp ->
                            Log.d("DABApi", "Response from endpoint ${index + 1}: ${resp.code} ${resp.message}")

                            if (!resp.isSuccessful) {
                                if (resp.code == 401 || resp.code == 403) {
                                    Log.w("DABApi", "Authentication failed, clearing session")
                                    clearSession()
                                }
                                continue
                            }

                            val body = resp.body?.string()
                            if (body.isNullOrEmpty()) {
                                Log.w("DABApi", "Empty response body from endpoint ${index + 1}")
                                continue
                            }

                            Log.d("DABApi", "Response body length: ${body.length} chars from endpoint ${index + 1}")

                            try {
                                // Try parsing with correct DAB API response format first
                                val response = json.decodeFromString<DabLibraryResponse>(body)
                                val tracks = response.library?.tracks?.mapNotNull { track ->
                                    try { converter.toTrack(track) } catch (_: Throwable) { null }
                                } ?: emptyList()

                                if (tracks.isNotEmpty()) {
                                    Log.i("DABApi", "SUCCESS: Found ${tracks.size} tracks for playlist ${playlist.id} from endpoint ${index + 1}")
                                    return@withContext tracks
                                }
                            } catch (e: Throwable) {
                                Log.d("DABApi", "Failed to parse as DabLibraryResponse from endpoint ${index + 1}: ${e.message}")

                                // Try fallback parsing
                                val fallbackTracks = jsonParsingUtils.parseTracksFromResponse(json.parseToJsonElement(body))
                                if (fallbackTracks.isNotEmpty()) {
                                    Log.i("DABApi", "SUCCESS: Parsed ${fallbackTracks.size} tracks using fallback parser from endpoint ${index + 1}")
                                    return@withContext fallbackTracks
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        Log.w("DABApi", "Exception calling endpoint ${index + 1}: ${e.message}")
                    }
                }

                Log.w("DABApi", "FAILED: No tracks found for playlist=${playlist.id} after trying all endpoints")
                emptyList()
            }
        } catch (e: Throwable) {
            Log.e("DABApi", "Exception in fetchAllPlaylistTracks for playlist=${playlist.id}: ${e.message}", e)
            emptyList()
        }
    }

    /** Parse track from JSON object manually */
    private fun tryParseManualTrack(el: JsonObject): DabTrack? {
        val track = jsonParsingUtils.tryParseTrackFromJsonObject(el)
        return if (track != null) {
            // Convert back to DabTrack if needed - this indicates we need to refactor the converter
            converter.toDabTrack(track)
        } else null
    }

    /** Get artist details by ID */
    fun getArtistDetails(artistId: String): DabArtist? {
        return try {
            Log.d("DABApi", "Fetching artist details for artistId: $artistId")

            // Try multiple endpoint formats according to DAB API specification
            val endpoints = listOf(
                "https://dab.yeet.su/api/artist/${URLEncoder.encode(artistId, "UTF-8")}", // Path parameter format
                "https://dab.yeet.su/api/artist?artistId=${URLEncoder.encode(artistId, "UTF-8")}", // Query parameter format
                "https://dab.yeet.su/api/artist?id=${URLEncoder.encode(artistId, "UTF-8")}" // Legacy format
            )

            for ((index, url) in endpoints.withIndex()) {
                try {
                    Log.d("DABApi", "Trying artist endpoint ${index + 1}/${endpoints.size}: $url")
                    val req = newRequestBuilder(url).build()
                    httpClient.newCall(req).execute().use { resp ->
                        Log.d("DABApi", "Artist endpoint ${index + 1} response: ${resp.code} ${resp.message}")

                        if (!resp.isSuccessful) {
                            if (resp.code == 404) {
                                Log.d("DABApi", "Artist not found at endpoint ${index + 1}, trying next")
                                continue
                            } else {
                                Log.w("DABApi", "Artist request failed at endpoint ${index + 1}: ${resp.code}")
                                continue
                            }
                        }

                        val body = resp.body?.string() ?: continue
                        Log.d("DABApi", "Artist response body length: ${body.length} chars")

                        try {
                            // Try direct artist parsing first
                            val artist = json.decodeFromString<DabArtist>(body)
                            Log.i("DABApi", "SUCCESS: Parsed artist '${artist.name}' from endpoint ${index + 1}")
                            return artist
                        } catch (e: Throwable) {
                            Log.d("DABApi", "Failed direct artist parsing from endpoint ${index + 1}: ${e.message}")

                            // Try structured response format
                            try {
                                val root = json.parseToJsonElement(body) as? JsonObject
                                val artistObj = root?.get("artist") as? JsonObject ?: root
                                if (artistObj != null) {
                                    val parsedArtist = json.decodeFromJsonElement(DabArtist.serializer(), artistObj)
                                    Log.i("DABApi", "SUCCESS: Parsed artist '${parsedArtist.name}' from structured response at endpoint ${index + 1}")
                                    return parsedArtist
                                }
                            } catch (e2: Throwable) {
                                Log.d("DABApi", "Structured artist parsing failed from endpoint ${index + 1}: ${e2.message}")

                                // Try manual parsing as last resort
                                try {
                                    val root = json.parseToJsonElement(body)
                                    val artist = parseArtistFromJsonElement(root)
                                    if (artist != null) {
                                        Log.i("DABApi", "SUCCESS: Manually parsed artist '${artist.name}' from endpoint ${index + 1}")
                                        return artist
                                    }
                                } catch (e3: Throwable) {
                                    Log.d("DABApi", "Manual artist parsing failed from endpoint ${index + 1}: ${e3.message}")
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.w("DABApi", "Exception calling artist endpoint ${index + 1}: ${e.message}")
                }
            }

            Log.w("DABApi", "FAILED: No artist found for artistId=$artistId after trying all endpoints")
            null
        } catch (e: Throwable) {
            Log.e("DABApi", "Exception in getArtistDetails for artistId=$artistId: ${e.message}", e)
            null
        }
    }

    /** Parse artist data from JSON element */
    private fun parseArtistFromJsonElement(root: JsonElement): DabArtist? {
        return try {
            when (root) {
                is JsonObject -> {
                    // Check if root contains artist data directly
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

                    DabArtist(
                        id = idInt,
                        name = name,
                        picture = picture
                    )
                }
                else -> null
            }
        } catch (e: Throwable) {
            Log.w("DABApi", "Failed to manually parse artist: ${e.message}")
            null
        }
    }

    /** Get artist's discography */
    fun getArtistDiscography(artistId: String): List<DabAlbum> {
        return try {
            Log.d("DABApi", "Fetching discography for artistId: $artistId")

            // Try multiple endpoint formats according to DAB API specification
            val endpoints = listOf(
                "https://dab.yeet.su/api/discography?artistId=${URLEncoder.encode(artistId, "UTF-8")}", // Standard format
                "https://dab.yeet.su/api/artist/${URLEncoder.encode(artistId, "UTF-8")}/albums", // RESTful format
                "https://dab.yeet.su/api/artist/${URLEncoder.encode(artistId, "UTF-8")}/discography", // Alternative RESTful
                "https://dab.yeet.su/api/albums?artistId=${URLEncoder.encode(artistId, "UTF-8")}", // Albums by artist
                "https://dab.yeet.su/api/discography/${URLEncoder.encode(artistId, "UTF-8")}" // Path parameter format
            )

            for ((index, url) in endpoints.withIndex()) {
                try {
                    Log.d("DABApi", "Trying discography endpoint ${index + 1}/${endpoints.size}: $url")
                    val req = newRequestBuilder(url).build()
                    httpClient.newCall(req).execute().use { resp ->
                        Log.d("DABApi", "Discography endpoint ${index + 1} response: ${resp.code} ${resp.message}")

                        if (!resp.isSuccessful) {
                            if (resp.code == 404) {
                                Log.d("DABApi", "Discography not found at endpoint ${index + 1}, trying next")
                                continue
                            } else {
                                Log.w("DABApi", "Discography request failed at endpoint ${index + 1}: ${resp.code}")
                                continue
                            }
                        }

                        val body = resp.body?.string() ?: continue
                        Log.d("DABApi", "Discography response body length: ${body.length} chars")

                        try {
                            val root = json.decodeFromString<JsonElement>(body)
                            val albums = parseDiscographyFromJsonElement(root)

                            if (albums.isNotEmpty()) {
                                Log.i("DABApi", "SUCCESS: Found ${albums.size} albums for artistId=$artistId from endpoint ${index + 1}")
                                return albums
                            } else {
                                Log.d("DABApi", "No albums found in response from endpoint ${index + 1}")
                            }
                        } catch (e: Throwable) {
                            Log.d("DABApi", "Failed to parse discography from endpoint ${index + 1}: ${e.message}")
                            Log.d("DABApi", "Response body preview: ${body.take(200)}")
                        }
                    }
                } catch (e: Throwable) {
                    Log.w("DABApi", "Exception calling discography endpoint ${index + 1}: ${e.message}")
                }
            }

            Log.w("DABApi", "FAILED: No discography found for artistId=$artistId after trying all endpoints")
            emptyList()
        } catch (e: Throwable) {
            Log.e("DABApi", "Exception in getArtistDiscography for artistId=$artistId: ${e.message}", e)
            emptyList()
        }
    }

    /** Parse discography from JSON element */
    private fun parseDiscographyFromJsonElement(root: JsonElement): List<DabAlbum> {
        val out = mutableListOf<DabAlbum>()

        try {
            when (root) {
                is JsonObject -> {
                    // Check for different response structures
                    val candidates = listOf("albums", "discography", "data", "items", "results", "content")

                    for (key in candidates) {
                        val arr = root[key] as? JsonArray
                        if (arr != null) {
                            Log.d("DABApi", "Found ${arr.size} items in '$key' array")
                            arr.forEach { el ->
                                if (el is JsonObject) {
                                    try {
                                        val album = json.decodeFromJsonElement(DabAlbum.serializer(), el)
                                        out.add(album)
                                    } catch (_: Throwable) {
                                        // Try manual album parsing
                                        val manualAlbum = parseAlbumFromJsonElement(el)
                                        if (manualAlbum != null) out.add(manualAlbum)
                                    }
                                }
                            }
                            if (out.isNotEmpty()) break
                        }
                    }

                    // If no arrays found, try parsing root as single album
                    if (out.isEmpty()) {
                        try {
                            val album = json.decodeFromJsonElement(DabAlbum.serializer(), root)
                            out.add(album)
                        } catch (_: Throwable) {
                            val manualAlbum = parseAlbumFromJsonElement(root)
                            if (manualAlbum != null) out.add(manualAlbum)
                        }
                    }
                }
                is JsonArray -> {
                    Log.d("DABApi", "Parsing direct array with ${root.size} items")
                    root.forEach { el ->
                        if (el is JsonObject) {
                            try {
                                val album = json.decodeFromJsonElement(DabAlbum.serializer(), el)
                                out.add(album)
                            } catch (_: Throwable) {
                                val manualAlbum = parseAlbumFromJsonElement(el)
                                if (manualAlbum != null) out.add(manualAlbum)
                            }
                        }
                    }
                }
                else -> {
                    Log.d("DABApi", "Unexpected JSON structure for discography: ${root::class.simpleName}")
                }
            }
        } catch (e: Throwable) {
            Log.w("DABApi", "Failed to parse discography: ${e.message}")
        }

        Log.d("DABApi", "Parsed ${out.size} albums from discography response")
        return out
    }

    // Add a debug helper method to diagnose playlist issues
    fun debugPlaylistIssues(playlist: Playlist): String {
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
                                val tracks = jsonParsingUtils.parseTracksFromResponse(root)
                                debug.appendLine("  Parsed Tracks: ${tracks.size}")
                                // Ensure tracks is a List<Track> so .title and .artists are valid
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

    /** Search all content types */
    suspend fun searchAll(query: String, limit: Int = 20): Triple<List<Track>, List<Album>, List<Artist>> {
        if (query.isBlank()) return Triple(emptyList<Track>(), emptyList<Album>(), emptyList<Artist>())

        return withContext(Dispatchers.IO) {
            try {
                Log.d("DABApi", "Performing unified search for query: '$query'")

                // Try unified search endpoint first (some DAB servers support this)
                val unifiedResults = tryUnifiedSearch(query, limit)
                if (unifiedResults != null) {
                    Log.i("DABApi", "Using unified search results - Tracks: ${unifiedResults.first.size}, Albums: ${unifiedResults.second.size}, Artists: ${unifiedResults.third.size}")
                    return@withContext unifiedResults
                }

                // Fallback to individual searches if unified search fails
                Log.d("DABApi", "Unified search failed, performing individual searches")
                val trackResults = async { searchByType(query, "track", limit) }
                val albumResults = async { searchByType(query, "album", limit) }
                val artistResults = async { searchByType(query, "artist", limit) }

                val tracks = trackResults.await().filterIsInstance<Track>()
                val albums = albumResults.await().filterIsInstance<Album>()
                val artists = artistResults.await().filterIsInstance<Artist>()

                Log.i("DABApi", "Individual search results - Tracks: ${tracks.size}, Albums: ${albums.size}, Artists: ${artists.size}")
                Triple(tracks, albums, artists)
            } catch (e: Throwable) {
                Log.e("DABApi", "Exception in searchAll: ${e.message}", e)
                Triple(emptyList<Track>(), emptyList<Album>(), emptyList<Artist>())
            }
        }
    }

    /** Try unified search API endpoint */
    private fun tryUnifiedSearch(query: String, limit: Int): Triple<List<Track>, List<Album>, List<Artist>>? {
        return try {
            val url = "https://dab.yeet.su/api/search?q=${URLEncoder.encode(query, "UTF-8")}&limit=$limit"
            Log.d("DABApi", "Trying unified search: $url")

            val req = newRequestBuilder(url, includeCookie = false).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.d("DABApi", "Unified search failed with ${resp.code}")
                    return null
                }

                val body = resp.body?.string()
                if (body.isNullOrEmpty()) {
                    Log.d("DABApi", "Empty unified search response")
                    return null
                }

                parseUnifiedSearchResponse(body)
            }
        } catch (e: Throwable) {
            Log.d("DABApi", "Unified search exception: ${e.message}")
            null
        }
    }

    /** Search by specific type */
    private suspend fun searchByType(query: String, type: String, limit: Int): List<Any> {
        if (query.isBlank()) return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                val url = "https://dab.yeet.su/api/search?q=${URLEncoder.encode(query, "UTF-8")}&type=$type&limit=$limit"
                Log.d("DABApi", "Searching $type: $url")

                val req = newRequestBuilder(url, includeCookie = false).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w("DABApi", "$type search failed: ${resp.code} ${resp.message}")
                        return@withContext emptyList()
                    }

                    val body = resp.body?.string()
                    if (body.isNullOrEmpty()) {
                        Log.w("DABApi", "Empty response body from $type search")
                        return@withContext emptyList()
                    }

                    Log.d("DABApi", "Search response body length: ${body.length}")
                    Log.d("DABApi", "Search response preview: ${body.take(500)}")

                    val results = when (type) {
                        "track" -> parseSearchResponse(body, ::parseTrackResult)
                        "album" -> parseSearchResponse(body, ::parseAlbumResult)
                        "artist" -> {
                            // Try to parse as artists first
                            val artistResults = parseSearchResponse(body, ::parseArtistResult)
                            if (artistResults.isNotEmpty()) {
                                artistResults
                            } else {
                                // If no artists found, extract unique artists from track results
                                Log.d("DABApi", "No direct artist results, extracting artists from track data")
                                extractArtistsFromResponse(body)
                            }
                        }
                        else -> emptyList()
                    }

                    Log.d("DABApi", "Parsed ${results.size} results for $type search")
                    return@withContext results
                }
            } catch (e: Throwable) {
                Log.e("DABApi", "Exception in search$type: ${e.message}", e)
                emptyList()
            }
        }
    }

    /** Extract artists from track search results */
    private fun extractArtistsFromResponse(body: String): List<Artist> {
        try {
            val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
            val tracksArray = root["tracks"] as? JsonArray ?: return emptyList()

            Log.d("DABApi", "Extracting artists from ${tracksArray.size} track results")

            val uniqueArtists = mutableMapOf<String, Artist>()

            for (element in tracksArray) {
                if (element !is JsonObject) continue

                try {
                    val artistName = (element["artist"] as? JsonPrimitive)?.content
                    val artistIdStr = (element["artistId"] as? JsonPrimitive)?.content
                    val albumCover = (element["albumCover"] as? JsonPrimitive)?.content

                    if (!artistName.isNullOrBlank() && !artistIdStr.isNullOrBlank()) {
                        val artistId = artistIdStr.toIntOrNull()
                        if (artistId != null && !uniqueArtists.containsKey(artistIdStr)) {
                            val dabArtist = DabArtist(
                                id = artistId,
                                name = artistName,
                                picture = albumCover // Use album cover as artist picture
                            )
                            val convertedArtist = converter.toArtist(dabArtist)
                            uniqueArtists[artistIdStr] = convertedArtist
                            Log.d("DABApi", "Extracted artist: '$artistName' (ID: $artistId)")
                        }
                    }
                } catch (e: Throwable) {
                    Log.w("DABApi", "Failed to extract artist from track: ${e.message}")
                }
            }

            val artistList = uniqueArtists.values.toList()
            Log.d("DABApi", "Extracted ${artistList.size} unique artists from track results")
            return artistList

        } catch (e: Throwable) {
            Log.w("DABApi", "Failed to extract artists from response: ${e.message}")
            return emptyList()
        }
    }

    /** Parse search response with specific parser */
    private fun parseSearchResponse(body: String, parser: (JsonObject) -> Any?): List<Any> {
        Log.d("DABApi", "Parsing search response with parser")

        // Try different response formats that the DAB API might return

        // 1. Try the actual DAB API format: {"results":[...]} (most common)
        try {
            val root = json.parseToJsonElement(body) as? JsonObject
            if (root != null) {
                Log.d("DABApi", "Parsing as object format")

                // First try the standard DAB API results array
                val resultsArray = root["results"] as? JsonArray
                if (resultsArray != null) {
                    Log.d("DABApi", "Found 'results' array with ${resultsArray.size} items")
                    val results = resultsArray.mapNotNull { element ->
                        if (element is JsonObject) {
                            val parsed = parser(element)
                            Log.v("DABApi", "Parsed result item: ${parsed?.javaClass?.simpleName}")
                            parsed
                        } else null
                    }
                    if (results.isNotEmpty()) {
                        Log.d("DABApi", "Successfully parsed ${results.size} items from 'results' array")
                        return results
                    }
                }

                // Then try type-specific arrays (for different API variations)
                val typeSpecificKeys = when (parser) {
                    ::parseTrackResult -> listOf("tracks", "songs")
                    ::parseAlbumResult -> listOf("albums", "releases")
                    ::parseArtistResult -> listOf("artists", "performers")
                    else -> listOf("tracks", "albums", "artists", "data", "items", "content")
                }

                for (key in typeSpecificKeys) {
                    val arr = root[key] as? JsonArray
                    if (arr != null) {
                        Log.d("DABApi", "Found array in key '$key' with ${arr.size} items")
                        val results = arr.mapNotNull { element ->
                            if (element is JsonObject) {
                                val parsed = parser(element)
                                Log.v("DABApi", "Parsed $key item: ${parsed?.javaClass?.simpleName}")
                                parsed
                            } else null
                        }
                        if (results.isNotEmpty()) {
                            Log.d("DABApi", "Successfully parsed ${results.size} items from '$key' array")
                            return results
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w("DABApi", "Object format parsing failed: ${e.message}")
        }

        // 2. Try structured DAB API search response (legacy format)
        try {
            val searchResponse = json.decodeFromString<DabSearchResponse>(body)
            Log.d("DABApi", "Successfully parsed as DabSearchResponse with ${searchResponse.results.size} results")
            return searchResponse.results.mapNotNull { result ->
                try {
                    // Convert DabSearchResult to JsonObject for unified parsing
                    val resultJson = json.encodeToJsonElement(DabSearchResult.serializer(), result) as JsonObject
                    val parsed = parser(resultJson)
                    Log.v("DABApi", "Parsed search result: ${parsed?.javaClass?.simpleName}")
                    parsed
                } catch (e: Throwable) {
                    Log.w("DABApi", "Failed to parse search result: ${e.message}")
                    null
                }
            }
        } catch (e: Throwable) {
            Log.d("DABApi", "Structured search response parsing failed: ${e.message}")
        }

        // 3. Try simple array format [item1, item2, ...]
        try {
            val rootArray = json.parseToJsonElement(body) as? JsonArray
            if (rootArray != null) {
                Log.d("DABApi", "Parsing as simple array format with ${rootArray.size} items")
                val results = rootArray.mapNotNull { element ->
                    if (element is JsonObject) {
                        val parsed = parser(element)
                        Log.v("DABApi", "Parsed array item: ${parsed?.javaClass?.simpleName}")
                        parsed
                    } else null
                }
                if (results.isNotEmpty()) return results
            }
        } catch (e: Throwable) {
            Log.d("DABApi", "Simple array parsing failed: ${e.message}")
        }

        // 4. Try single item response
        try {
            val singleItem = json.parseToJsonElement(body) as? JsonObject
            if (singleItem != null) {
                Log.d("DABApi", "Trying to parse as single item")
                val parsed = parser(singleItem)
                if (parsed != null) {
                    Log.d("DABApi", "Successfully parsed single item: ${parsed.javaClass.simpleName}")
                    return listOf(parsed)
                }
            }
        } catch (e: Throwable) {
            Log.d("DABApi", "Single item parsing failed: ${e.message}")
        }

        Log.w("DABApi", "All parsing attempts failed, returning empty list")
        Log.w("DABApi", "Response body preview: ${body.take(500)}")
        return emptyList()
    }

    /** Parse unified search response */
    private fun parseUnifiedSearchResponse(body: String): Triple<List<Track>, List<Album>, List<Artist>>? {
        try {
            val root = json.parseToJsonElement(body) as? JsonObject ?: return null

            val tracks = mutableListOf<Track>()
            val albums = mutableListOf<Album>()
            val artists = mutableListOf<Artist>()

            // Check for structured response with separate sections
            root["tracks"]?.let { tracksElement ->
                tracks.addAll(parseGenericSearchResponse(tracksElement, ::parseTrackResult).filterIsInstance<Track>())
            }
            root["albums"]?.let { albumsElement ->
                albums.addAll(parseGenericSearchResponse(albumsElement, ::parseAlbumResult).filterIsInstance<Album>())
            }
            root["artists"]?.let { artistsElement ->
                artists.addAll(parseGenericSearchResponse(artistsElement, ::parseArtistResult).filterIsInstance<Artist>())
            }

            // If we found structured data, return it
            if (tracks.isNotEmpty() || albums.isNotEmpty() || artists.isNotEmpty()) {
                return Triple(tracks, albums, artists)
            }

            // Otherwise try to parse mixed results
            val results = root["results"] as? JsonArray ?: root["data"] as? JsonArray ?: return null
            for (element in results) {
                if (element !is JsonObject) continue

                // Try to determine type and parse accordingly
                val type = (element["type"] as? JsonPrimitive)?.content
                when (type?.lowercase()) {
                    "track", "song" -> {
                        parseTrackResult(element)?.let { tracks.add(it) }
                    }
                    "album" -> {
                        parseAlbumResult(element)?.let { albums.add(it) }
                    }
                    "artist" -> {
                        parseArtistResult(element)?.let { artists.add(it) }
                    }
                    else -> {
                        // Try to infer type from available fields
                        when {
                            element.containsKey("duration") || element.containsKey("trackId") -> {
                                parseTrackResult(element)?.let { tracks.add(it) }
                            }
                            element.containsKey("trackCount") || element.containsKey("tracks") -> {
                                parseAlbumResult(element)?.let { albums.add(it) }
                            }
                            element.containsKey("albumsCount") || element.containsKey("picture") -> {
                                parseArtistResult(element)?.let { artists.add(it) }
                            }
                        }
                    }
                }
            }

            return Triple(tracks, albums, artists)
        } catch (e: Throwable) {
            Log.w("DABApi", "Failed to parse unified search response: ${e.message}")
            return null
        }
    }

    /** Generic search response parser for arrays/objects */
    private fun parseGenericSearchResponse(root: JsonElement, parser: (JsonObject) -> Any?): List<Any> {
        val results = mutableListOf<Any>()

        when (root) {
            is JsonArray -> {
                for (element in root) {
                    if (element is JsonObject) {
                        parser(element)?.let { results.add(it) }
                    }
                }
            }
            is JsonObject -> {
                // Check common array containers
                val candidates = listOf("results", "data", "items", "tracks", "albums", "artists")
                for (key in candidates) {
                    val arr = root[key] as? JsonArray
                    if (arr != null) {
                        for (element in arr) {
                            if (element is JsonObject) {
                                parser(element)?.let { results.add(it) }
                            }
                        }
                        if (results.isNotEmpty()) break
                    }
                }
            }
            else -> { }
        }

        return results
    }

    /** Parse track from search result */
    private fun parseTrackResult(el: JsonObject): Track? {
        return try {
            // Try direct DabTrack parsing first
            val dabTrack = json.decodeFromJsonElement(DabTrack.serializer(), el)
            val convertedTrack = converter.toTrack(dabTrack)
            Log.d("DABApi", "Successfully parsed track: '${convertedTrack.title}' by '${convertedTrack.artists.firstOrNull()?.name}'")
            convertedTrack
        } catch (e: Throwable) {
            Log.d("DABApi", "Direct DabTrack parsing failed: ${e.message}, trying manual parsing")

            // Enhanced manual parsing for tracks
            try {
                val idEl = el["id"] ?: el["trackId"] ?: el["track_id"] ?: return null
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
                    ?: (el["cover"] as? JsonPrimitive)?.content
                val albumId = (el["albumId"] as? JsonPrimitive)?.content
                val releaseDate = (el["releaseDate"] as? JsonPrimitive)?.content
                val genre = (el["genre"] as? JsonPrimitive)?.content
                val duration = (el["duration"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

                Log.d("DABApi", "Manual parsing - ID: $idInt, Title: '$title', Artist: '$artist'")

                val dabTrack = DabTrack(
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
                val convertedTrack = converter.toTrack(dabTrack)
                Log.d("DABApi", "Manual parsing successful: '${convertedTrack.title}' by '${convertedTrack.artists.firstOrNull()?.name}'")
                convertedTrack
            } catch (e2: Throwable) {
                Log.w("DABApi", "Failed to parse track result manually: ${e2.message}")
                Log.w("DABApi", "JsonObject contents: $el")
                null
            }
        }
    }

    /** Parse album from search result */
    private fun parseAlbumResult(el: JsonObject): Album? {
        return try {
            // Try direct DabAlbum parsing first
            val dabAlbum = json.decodeFromJsonElement(DabAlbum.serializer(), el)
            val convertedAlbum = converter.toAlbum(dabAlbum)
            Log.d("DABApi", "Successfully parsed album: '${convertedAlbum.title}' by '${convertedAlbum.artists.firstOrNull()?.name}'")
            convertedAlbum
        } catch (e: Throwable) {
            Log.d("DABApi", "Direct DabAlbum parsing failed: ${e.message}, trying manual parsing")

            // Enhanced manual parsing for albums
            try {
                val id = (el["id"] as? JsonPrimitive)?.content
                    ?: (el["albumId"] as? JsonPrimitive)?.content ?: return null
                val title = (el["title"] as? JsonPrimitive)?.content
                    ?: (el["name"] as? JsonPrimitive)?.content ?: "Album"
                val artist = (el["artist"] as? JsonPrimitive)?.content
                    ?: (el["artistName"] as? JsonPrimitive)?.content ?: ""
                val artistId = (el["artistId"] as? JsonPrimitive)?.content?.toIntOrNull()
                val cover = (el["cover"] as? JsonPrimitive)?.content
                    ?: (el["albumCover"] as? JsonPrimitive)?.content
                val releaseDate = (el["releaseDate"] as? JsonPrimitive)?.content
                val trackCount = (el["trackCount"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

                Log.d("DABApi", "Manual album parsing - ID: $id, Title: '$title', Artist: '$artist', TrackCount: $trackCount")

                val dabAlbum = DabAlbum(
                    id = id,
                    title = title,
                    artist = artist,
                    artistId = artistId,
                    cover = cover,
                    releaseDate = releaseDate,
                    trackCount = trackCount,
                    tracks = null
                )
                val convertedAlbum = converter.toAlbum(dabAlbum)
                Log.d("DABApi", "Manual album parsing successful: '${convertedAlbum.title}' by '${convertedAlbum.artists.firstOrNull()?.name}'")
                convertedAlbum
            } catch (e2: Throwable) {
                Log.w("DABApi", "Failed to parse album result manually: ${e2.message}")
                Log.w("DABApi", "JsonObject contents: $el")
                null
            }
        }
    }

    /** Parse artist from search result */
    private fun parseArtistResult(el: JsonObject): Artist? {
        return try {
            // Try direct DabArtist parsing first
            val dabArtist = json.decodeFromJsonElement(DabArtist.serializer(), el)
            val convertedArtist = converter.toArtist(dabArtist)
            Log.d("DABApi", "Successfully parsed artist: '${convertedArtist.name}'")
            convertedArtist
        } catch (e: Throwable) {
            Log.d("DABApi", "Direct DabArtist parsing failed: ${e.message}, trying manual parsing")

            // Enhanced manual parsing for artists
            try {
                val idEl = el["id"] ?: el["artistId"] ?: return null
                val idStr = (idEl as? JsonPrimitive)?.content ?: idEl.toString()
                val idInt = idStr.toIntOrNull() ?: return null

                val name = (el["name"] as? JsonPrimitive)?.content
                    ?: (el["title"] as? JsonPrimitive)?.content
                    ?: (el["artistName"] as? JsonPrimitive)?.content ?: "Artist"
                val picture = (el["picture"] as? JsonPrimitive)?.content
                    ?: (el["cover"] as? JsonPrimitive)?.content
                    ?: (el["image"] as? JsonPrimitive)?.content

                val dabArtist = DabArtist(
                    id = idInt,
                    name = name,
                    picture = picture
                )
                val convertedArtist = converter.toArtist(dabArtist)
                Log.d("DABApi", "Manual artist parsing successful: '${convertedArtist.name}'")
                convertedArtist
            } catch (e2: Throwable) {
                Log.w("DABApi", "Failed to parse artist result manually: ${e2.message}")
                Log.w("DABApi", "JsonObject contents: $el")
                null
            }
        }
    }

    // Search convenience methods
    suspend fun searchTracks(query: String, limit: Int = 20): List<Track> {
        return searchByType(query, "track", limit).filterIsInstance<Track>()
    }

    suspend fun searchAlbums(query: String, limit: Int = 20): List<Album> {
        return searchByType(query, "album", limit).filterIsInstance<Album>()
    }

    suspend fun searchArtists(query: String, limit: Int = 20): List<Artist> {
        return searchByType(query, "artist", limit).filterIsInstance<Artist>()
    }
}
