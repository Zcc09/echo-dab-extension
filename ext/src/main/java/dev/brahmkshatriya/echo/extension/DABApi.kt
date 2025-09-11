package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.models.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

class DABApi(
    private val httpClient: OkHttpClient,
    private val converter: Converter,
    private val settings: Settings
) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    // Helper to normalize cookie header value. Stored setting may be either "session=..." or just the token.
    private fun cookieHeaderValue(): String? {
        val raw = settings.getString("session_cookie") ?: return null
        return if (raw.contains('=')) raw else "session=$raw"
    }

    // Simple debug hook to verify this DABApi instance is running same code as extension
    fun debugPing(tag: String = "ping") { /* intentionally empty in production build */ }

    fun loginAndSaveCookie(email: String, pass: String): User {
        val loginRequest = DabLoginRequest(email, pass)
        val requestBody = json.encodeToString(DabLoginRequest.serializer(), loginRequest)
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://dab.yeet.su/api/auth/login")
            .post(requestBody)
            .build()

        val response = httpClient.newCall(request).execute()

        val cookieHeader = response.headers["Set-Cookie"]
        if (cookieHeader != null) {
            val sessionCookie = cookieHeader.split(';').firstOrNull { it.trim().startsWith("session=") }
            if (sessionCookie != null) {
                settings.putString("session_cookie", sessionCookie)
            }
        }

        val body = response.body?.string() ?: error("Empty response body")
        if (!response.isSuccessful) {
            error("API Error: ${response.code} ${response.message} - $body")
        }
        val userResponse: DabUserResponse = json.decodeFromString(body)
        return converter.toUser(userResponse.user ?: error("User data is null after login"))
    }

    private inline fun <reified T> executeAuthenticated(url: String): T {
        val cookie = cookieHeaderValue() ?: error("Not logged in: session cookie not found.")

        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("Accept", "application/json")
            .header("User-Agent", "EchoDAB-Extension/1.0")
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: error("Empty response body")

        if (!response.isSuccessful) {
            error("API Error: ${response.code} ${response.message} - $body")
        }
        return json.decodeFromString(body)
    }

    fun getMe(): User {
        val response: DabUserResponse = executeAuthenticated("https://dab.yeet.su/api/auth/me")
        return converter.toUser(response.user ?: error("User data is null"))
    }

    fun getStreamUrl(trackId: String): String {
        val cookie = cookieHeaderValue()
        val url = "https://dab.yeet.su/api/stream?trackId=$trackId"
        val requestBuilder = Request.Builder().url(url)
        if (!cookie.isNullOrBlank()) {
            requestBuilder.header("Cookie", cookie)
        }
        requestBuilder.header("Accept", "application/json").header("User-Agent", "EchoDAB-Extension/1.0")
        val request = requestBuilder.build()

        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Empty response"
            error("API Error: ${response.code} - $errorBody")
        }

        // Check if response is a redirect (302) with Location header
        val location = response.header("Location")
        if (location != null) {
            return location
        }

        // Read the body only after checking for redirects
        val body = response.body?.string() ?: error("Empty response body")

        return try {
            val streamResponse: DabStreamResponse = json.decodeFromString(body)
            streamResponse.streamUrl ?: streamResponse.url ?: streamResponse.stream ?: streamResponse.link
            ?: error("No stream URL found in JSON response")
        } catch (_: kotlinx.serialization.SerializationException) {
            // If JSON parsing fails, check if response is a direct URL
            if (body.trim().startsWith("http")) {
                body.trim()
            } else {
                error("API did not return a stream URL.")
            }
        }
    }

    @Suppress("unused")
    fun getLibraryPlaylists(page: Int, pageSize: Int): PagedData<Playlist> {
        return PagedData.Continuous { continuation ->
            val pageNum = continuation?.toIntOrNull() ?: page
            val url = "https://dab.yeet.su/api/libraries?limit=$pageSize&offset=${(pageNum - 1) * pageSize}"
            val response: DabPlaylistResponse = executeAuthenticated(url)
            val playlists = response.libraries.map(converter::toPlaylist)
            Page(playlists, if (playlists.isNotEmpty()) (pageNum + 1).toString() else null)
        }
    }

    // Fetch a concrete page of library playlists (materialized list) for use by the UI
    fun fetchLibraryPlaylistsPage(page: Int = 1, pageSize: Int = 50): List<Playlist> {
        val url = "https://dab.yeet.su/api/libraries?limit=$pageSize&offset=${(page - 1) * pageSize}"
        return try {
            val response: DabPlaylistResponse = executeAuthenticated(url)
            response.libraries.map(converter::toPlaylist)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun getPlaylistTracks(playlist: Playlist, page: Int, pageSize: Int): PagedData<Track> {
        return PagedData.Continuous { continuation ->
            val pageNum = continuation?.toIntOrNull() ?: page
            val url = "https://dab.yeet.su/api/libraries/${playlist.id}?limit=$pageSize&offset=${(pageNum - 1) * pageSize}"
            val response: DabLibraryTracksResponse = executeAuthenticated(url)
            val tracks = response.library.tracks.map(converter::toTrack)
            val nextContinuation = if (response.library.pagination.hasMore) (pageNum + 1).toString() else null
            Page(tracks, nextContinuation)
        }
    }

    fun search(query: String, page: Int, pageSize: Int): PagedData<Track> {
        return PagedData.Continuous { continuation ->
            val pageNum = continuation?.toIntOrNull() ?: page
            val url = "https://dab.yeet.su/api/search?q=$query&limit=$pageSize&offset=${(pageNum - 1) * pageSize}"

            val request = Request.Builder()
                .url(url)
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: error("Empty response body")

            if (!response.isSuccessful) {
                error("API Error: ${response.code} ${response.message} - $body")
            }

            val trackResponse: DabTrackResponse = json.decodeFromString(body)
            val tracks = trackResponse.tracks.map(converter::toTrack)
            Page(tracks, if (tracks.isNotEmpty()) (pageNum + 1).toString() else null)
        }
    }

    fun searchAlbums(query: String, limit: Int = 5): List<dev.brahmkshatriya.echo.common.models.Album> {
        val url = "https://dab.yeet.su/api/search/albums?q=$query&limit=$limit&offset=0"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            return emptyList()
        }

        val body = response.body?.string() ?: return emptyList()

        return try {
            val albumResponse: DabAlbumResponse = json.decodeFromString(body)
            albumResponse.albums.map(converter::toAlbum)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun searchArtists(query: String, limit: Int = 5): List<dev.brahmkshatriya.echo.common.models.Artist> {
        val url = "https://dab.yeet.su/api/search/artists?q=$query&limit=$limit&offset=0"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) return emptyList()

        val body = response.body?.string() ?: return emptyList()

        return try {
            val artistResponse: DabArtistResponse = json.decodeFromString(body)
            artistResponse.artists.map(converter::toArtist)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun searchTracks(query: String, limit: Int = 5): List<Track> {
        val url = "https://dab.yeet.su/api/search?q=$query&limit=$limit&offset=0"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) return emptyList()

        val body = response.body?.string() ?: return emptyList()

        return try {
            val trackResponse: DabTrackResponse = json.decodeFromString(body)
            trackResponse.tracks.map(converter::toTrack)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getArtistDetails(artistId: String): DabArtist? {
        val url = "https://dab.yeet.su/api/artists/$artistId"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return null
        val body = response.body?.string() ?: return null
        return try {
            val artistResponse: DabArtistResponse = json.decodeFromString(body)
            artistResponse.artists.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    fun getArtistDiscography(artistId: String): List<DabAlbum> {
        val url = "https://dab.yeet.su/api/discography?artistId=$artistId"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return emptyList()
        val body = response.body?.string() ?: return emptyList()
        return try {
            val discographyResponse: DabDiscographyResponse = json.decodeFromString(body)
            discographyResponse.albums
        } catch (_: Exception) {
            emptyList()
        }
    }

    // Fetch a single album by id. Tries common shapes: { album: {...} } or direct album object.
    fun getAlbum(albumId: String): DabAlbum? {
        val url = "https://dab.yeet.su/api/album/$albumId"
        try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            try {
                val single: DabSingleAlbumResponse = json.decodeFromString(body)
                return single.album
            } catch (_: Exception) {
                // try direct album response
            }
            return try {
                val album: DabAlbum = json.decodeFromString(body)
                album
            } catch (_: Exception) {
                null
            }
        } catch (_: Throwable) {
            return null
        }
    }

    @Suppress("unused")
    fun getArtistImage(artistId: String): String? {
        val url = "https://dab.yeet.su/api/artists/$artistId"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) return null

        val body = response.body?.string() ?: return null

        return try {
            val artistResponse: DabArtistResponse = json.decodeFromString(body)
            artistResponse.artists.firstOrNull()?.picture
        } catch (_: Exception) {
            null
        }
    }

    // Fetch lyrics for a track by artist+title. Returns raw lyrics text or null.
    fun getLyrics(artist: String, title: String): String? {
        try {
            val a = URLEncoder.encode(artist, "UTF-8")
            val t = URLEncoder.encode(title, "UTF-8")
            val url = "https://dab.yeet.su/api/lyrics?artist=$a&title=$t"
            val cookie = cookieHeaderValue()
            val reqBuilder = Request.Builder().url(url)
            if (!cookie.isNullOrBlank()) reqBuilder.header("Cookie", cookie)
            reqBuilder.header("Accept", "application/json").header("User-Agent", "EchoDAB-Extension/1.0")
            val req = reqBuilder.build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            try {
                // Try to parse structured response
                val lr: DabLyricsResponse = json.decodeFromString(body)
                if (!lr.lyrics.isNullOrBlank()) return lr.lyrics
            } catch (_: Exception) {
                // fallback to raw body
            }
            return body.trim().takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            return null
        }
    }

    // Best-effort check if a track is favorited. Because API doesn't expose a direct check,
    // fetch a single favorites page and search for the id. This is intentionally conservative.
    fun isTrackFavorited(trackId: String): Boolean {
        try {
            val page = fetchFavoritesPage(1, 500)
            return page.any { it.id == trackId }
        } catch (_: Throwable) {
            return false
        }
    }

    // Like or unlike a track. Uses POST /favorites and DELETE /favorites?trackId=... per API spec.
    fun likeTrack(trackId: String, shouldLike: Boolean): Boolean {
        val cookie = cookieHeaderValue() ?: return false
         return try {
             if (shouldLike) {
                 val payload = "{\"track\":{\"id\":\"$trackId\"}}"
                 val rb = payload.toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url("https://dab.yeet.su/api/favorites").header("Cookie", cookie).header("Accept", "application/json").post(rb).build()
                 val resp = httpClient.newCall(req).execute()
                 resp.isSuccessful
             } else {
                 val encoded = URLEncoder.encode(trackId, "UTF-8")
                val req = Request.Builder().url("https://dab.yeet.su/api/favorites?trackId=$encoded").header("Cookie", cookie).header("Accept", "application/json").delete().build()
                 val resp = httpClient.newCall(req).execute()
                 resp.isSuccessful
             }
         } catch (_: Throwable) {
             false
         }
     }

     // Fetch user's favorite / liked tracks. Simpler implementation: call the documented /favorites endpoint
     fun getFavorites(limit: Int = 200, offset: Int = 0): List<Track> {
        val url = "https://dab.yeet.su/api/favorites?limit=$limit&offset=$offset"

        fun parseTracks(body: String): List<Track> {
            try {
                val elem = json.parseToJsonElement(body)
                if (elem is JsonObject) {
                    val favs = elem["favorites"] ?: elem["tracks"] ?: elem["data"] ?: elem["items"]
                    if (favs is JsonArray) {
                        val dabTracks = favs.mapNotNull { it as? JsonObject }
                            .map { json.decodeFromJsonElement(DabTrack.serializer(), it) }
                        return dabTracks.map(converter::toTrack)
                    }
                }
            } catch (_: Exception) {
                // ignore
            }

            // Try direct decode to DabTrackResponse
            try {
                val trackResponse: DabTrackResponse = json.decodeFromString(body)
                return trackResponse.tracks.map(converter::toTrack)
            } catch (_: Exception) {
                // fallthrough to recursive search below
            }

            // Recursive search: find any JsonArray in the document whose elements are objects
            // and attempt to decode the elements individually as DabTrack. This handles
            // nested shapes like { data: { favorites: [...] } } or paged envelopes.
            try {
                val root = json.parseToJsonElement(body)
                val arrays = mutableListOf<JsonArray>()
                fun collectArrays(el: kotlinx.serialization.json.JsonElement) {
                    when (el) {
                        is JsonArray -> {
                            arrays.add(el)
                            el.forEach { collectArrays(it) }
                        }
                        is JsonObject -> {
                            el.values.forEach { collectArrays(it) }
                        }
                        else -> {}
                    }
                }
                collectArrays(root)

                for (arr in arrays) {
                    // require at least one object-like element
                    if (arr.isEmpty()) continue
                    val first = arr.first()
                    if (first !is JsonObject) continue
                    val parsedTracks = mutableListOf<Track>()
                    for (el in arr) {
                        if (el !is JsonObject) continue
                        try {
                            val dt = json.decodeFromJsonElement(DabTrack.serializer(), el)
                            parsedTracks.add(converter.toTrack(dt))
                        } catch (_: Exception) {
                            // element didn't match DabTrack shape; try heuristic field mapping
                            try {
                                val idVal = (el["id"] as? JsonPrimitive)?.content ?: (el["trackId"] as? JsonPrimitive)?.content
                                val title = (el["title"] as? JsonPrimitive)?.content ?: (el["name"] as? JsonPrimitive)?.content ?: ""
                                val artist = (el["artist"] as? JsonPrimitive)?.content ?: (el["artistName"] as? JsonPrimitive)?.content ?: ""
                                val duration = (el["duration"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                                if (idVal != null && title.isNotBlank()) {
                                    val dab = DabTrack(
                                        id = idVal.toIntOrNull() ?: 0,
                                        title = title,
                                        artist = artist,
                                        artistId = (el["artistId"] as? JsonPrimitive)?.content?.toIntOrNull(),
                                        albumTitle = (el["albumTitle"] as? JsonPrimitive)?.content,
                                        albumCover = (el["albumCover"] as? JsonPrimitive)?.content,
                                        albumId = (el["albumId"] as? JsonPrimitive)?.content,
                                        releaseDate = (el["releaseDate"] as? JsonPrimitive)?.content,
                                        genre = (el["genre"] as? JsonPrimitive)?.content,
                                        duration = duration,
                                        audioQuality = null
                                    )
                                    parsedTracks.add(converter.toTrack(dab))
                                }
                            } catch (_: Exception) {
                                // give up on this element
                            }
                        }
                    }
                    if (parsedTracks.isNotEmpty()) return parsedTracks
                }
            } catch (_: Exception) {
                // ignore
            }

            return emptyList()
        }

        fun tryRequestWithBuilder(builder: Request.Builder): List<Track> {
            return try {
                val req = builder.header("Accept", "application/json").header("User-Agent", "EchoDAB-Extension/1.0").build()
                val resp = httpClient.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return emptyList()
                return parseTracks(body)
            } catch (_: Throwable) {
                emptyList()
            }
        }

        // 1) Try with normalized Cookie (session=...)
        val cookieHeader = cookieHeaderValue()
        if (!cookieHeader.isNullOrBlank()) {
            val res = tryRequestWithBuilder(Request.Builder().url(url).header("Cookie", cookieHeader))
            if (res.isNotEmpty()) return res
        }

        // 2) If cookie-based request returned nothing, try Authorization: Bearer <token> (token extracted from cookie)
        val raw = settings.getString("session_cookie")
        val token = when {
            raw.isNullOrBlank() -> null
            raw.contains('=') -> raw.substringAfter('=')
            else -> raw
        }

        if (!token.isNullOrBlank()) {
            val resAuth = tryRequestWithBuilder(Request.Builder().url(url).header("Authorization", "Bearer $token"))
            if (resAuth.isNotEmpty()) return resAuth

            // 3) Try alternate cookie names that some servers accept
            val alternates = listOf("token", "auth", "session_id")
            for (name in alternates) {
                val ch = "$name=$token"
                val r = tryRequestWithBuilder(Request.Builder().url(url).header("Cookie", ch))
                if (r.isNotEmpty()) return r
            }
        }

        // 4) Final fallback: try without auth headers (public endpoints)
        val resPublic = tryRequestWithBuilder(Request.Builder().url(url))
        return resPublic
     }

    // Simplified single-page fetch that calls the documented /favorites endpoint
    fun fetchFavoritesPage(pageIndex: Int = 1, pageSize: Int = 200): List<Track> {
        val limit = pageSize
        val offset = (pageIndex - 1) * pageSize
        return getFavorites(limit, offset)
    }
}
