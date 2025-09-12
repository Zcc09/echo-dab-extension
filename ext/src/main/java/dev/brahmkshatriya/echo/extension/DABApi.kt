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
                error("API Error: ${response.code} ${response.message} - $body")
            }
            return json.decodeFromString(body)
        }
    }

    fun getMe(): User {
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
            val fromService = playlistService.fetchTracksForPlaylistSync(playlist, 1, pageSize)
            if (fromService.isNotEmpty() && fromService.size >= pageSize) {
                return fromService
            }

            withContext(Dispatchers.IO) {
                val out = mutableListOf<Track>()
                val candidateBasePaths = listOf(
                    "https://dab.yeet.su/api/libraries/${URLEncoder.encode(playlist.id, "UTF-8")}",
                    "https://dab.yeet.su/api/libraries/${URLEncoder.encode(playlist.id, "UTF-8")}/tracks",
                    "https://dab.yeet.su/api/playlists/${URLEncoder.encode(playlist.id, "UTF-8")}/tracks",
                    "https://dab.yeet.su/api/playlists/${URLEncoder.encode(playlist.id, "UTF-8")}",
                    "https://dab.yeet.su/api/libraries/${URLEncoder.encode(playlist.id, "UTF-8")}/items"
                )

                for (base in candidateBasePaths) {
                    try {
                        var page = 1
                        var totalFetched = 0
                        while (true) {
                            val url = "$base?page=$page&limit=$pageSize"
                            val rb = Request.Builder().url(url)
                            val cookie = cookieHeaderValue()
                            if (!cookie.isNullOrBlank()) rb.header("Cookie", cookie)
                            rb.header("Accept", "application/json").header("User-Agent", "EchoDAB-Extension/1.0")
                            httpClient.newCall(rb.build()).execute().use { resp ->
                                if (!resp.isSuccessful) break
                                val body = resp.body?.string() ?: break
                                try {
                                    val root = json.parseToJsonElement(body)
                                    val parsed = parseTracksFromResponse(root)

                                    if (parsed.isEmpty()) break
                                    out.addAll(parsed)
                                    totalFetched += parsed.size

                                    if (parsed.size >= pageSize) {
                                        page++
                                    } else {
                                        break
                                    }
                                } catch (_: Throwable) { break }
                            }
                        }
                        Log.d("DABApi", "Fetched $totalFetched total tracks for playlist ${playlist.id} from $base")
                    } catch (_: Throwable) { /* next candidate */ }
                    if (out.isNotEmpty()) return@withContext out
                }
                out
            }
        } catch (_: Throwable) { emptyList() }
    }

    private fun parseTracksFromResponse(root: JsonElement): List<Track> {
        val parsed = mutableListOf<Track>()

        if (root is JsonObject) {
            val arr = (root["tracks"] as? JsonArray) ?: (root["data"] as? JsonArray) ?: (root["items"] as? JsonArray) ?: (root["results"] as? JsonArray)
            if (arr != null) {
                for (el in arr) if (el is JsonObject) try {
                    val dt = json.decodeFromJsonElement(DabTrack.serializer(), el)
                    converter.toTrack(dt).let { parsed.add(it) }
                } catch (_: Throwable) { }
            }
        } else if (root is JsonArray) {
            for (el in root) if (el is JsonObject) try {
                val dt = json.decodeFromJsonElement(DabTrack.serializer(), el)
                converter.toTrack(dt).let { parsed.add(it) }
            } catch (_: Throwable) { }
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
}
