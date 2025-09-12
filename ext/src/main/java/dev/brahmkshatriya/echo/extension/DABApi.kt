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
import kotlinx.serialization.json.JsonElement
import dev.brahmkshatriya.echo.common.models.Album as CommonAlbum
import dev.brahmkshatriya.echo.common.models.Artist as CommonArtist
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.net.URLEncoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.random.Random
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi


class DABApi(
    private val httpClient: OkHttpClient,
    private val converter: Converter,
    private val settings: Settings
) {

    // Small in-memory TTL caches to reduce DAB search traffic when resolving Last.fm fallbacks
    @Suppress("unused")
    private val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
    @Suppress("unused")
    private val artistSearchCache = ConcurrentHashMap<String, Pair<Long, List<CommonArtist>>>()
    @Suppress("unused")
    private val trackSearchCache = ConcurrentHashMap<String, Pair<Long, List<Track>>>()
    private val trackStreamCache = ConcurrentHashMap<String, Pair<Long, String>>() // id -> (timestamp, resolvedUrl)
    private val STREAM_CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
    // Pending in-flight stream resolution requests to coalesce concurrent lookups
    private val pendingStreamRequests = ConcurrentHashMap<String, CompletableDeferred<String>>()
    // Combined search result container used by searchAll/searchUnified
    data class SearchResults(
        val tracks: List<Track>,
        val albums: List<CommonAlbum>,
        val artists: List<CommonArtist>
    )

    // Simple short-lived search cache to reduce repeated server queries for the same query
    private val searchCache = ConcurrentHashMap<String, Pair<Long, SearchResults>>()
    private val SEARCH_CACHE_TTL_MS = 60 * 1000L // 1 minute

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    // Load persisted stream cache entries from settings so resolved CDN URLs survive restarts
    init {
        try { loadPersistentStreamCache() } catch (_: Throwable) { /* best-effort */ }
    }

    private fun loadPersistentStreamCache() {
        val keys = settings.getString("stream_cache_keys") ?: return
        if (keys.isBlank()) return
        val now = System.currentTimeMillis()
        val ids = keys.split(',').mapNotNull { it.trim().takeIf { s -> s.isNotBlank() } }
        val builder = mutableListOf<String>()
        for (id in ids) {
            val raw = settings.getString("stream_cache_$id") ?: continue
            val parts = raw.split('|', limit = 2)
            if (parts.isEmpty()) continue
            val ts = parts.getOrNull(0)?.toLongOrNull() ?: continue
            val url = parts.getOrNull(1) ?: continue
            if (now - ts <= STREAM_CACHE_TTL_MS) {
                try { trackStreamCache[id] = ts to url } catch (_: Throwable) {}
                builder.add(id)
            } else {
                // expired - remove persisted entry
                settings.putString("stream_cache_$id", null)
            }
        }
        // rewrite keys to only include live entries
        if (builder.isNotEmpty()) settings.putString("stream_cache_keys", builder.joinToString(","))
        else settings.putString("stream_cache_keys", null)
    }

    private fun persistStreamCacheEntry(trackId: String, url: String) {
        try {
            val ts = System.currentTimeMillis()
            settings.putString("stream_cache_$trackId", "$ts|$url")
            val existing = settings.getString("stream_cache_keys")
            val set = existing?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
            set.add(trackId)
            settings.putString("stream_cache_keys", set.joinToString(","))
        } catch (_: Throwable) {
            // ignore persistence failures
        }
    }

    // Helper to safely extract string content from a JsonElement (handles missing fields)
    // Helper to safely extract string content from a JsonElement (handles missing fields)
    @Suppress("unused")
    private fun jsonElementAsString(el: JsonElement?): String? {
        return when (el) {
            is JsonPrimitive -> el.content
            else -> null
        }
    }

    private fun cookieHeaderValue(): String? {
        val raw = settings.getString("session_cookie") ?: return null
        return if (raw.contains('=')) raw else "session=$raw"
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
        val cookie = cookieHeaderValue() ?: error("Not logged in: session cookie not found.")
        val req = Request.Builder().url(url).header("Cookie", cookie).header("Accept", "application/json").header("User-Agent", "EchoDAB-Extension/1.0").build()
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

    // Resolve the API /api/stream endpoint by following redirects or parsing JSON bodies.
    // Returns a concrete CDN URL or null when it can't be resolved.
    fun resolveApiStreamEndpoint(endpointUrl: String): String? {
        try {
            val req = newRequestBuilder(endpointUrl).build()
            httpClient.newCall(req).execute().use { resp ->
                val location = resp.header("Location")
                if (!location.isNullOrBlank()) return location
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                try {
                    val sr: DabStreamResponse = json.decodeFromString(body)
                    return sr.streamUrl ?: sr.url ?: sr.stream ?: sr.link
                } catch (_: Throwable) {
                    // Not JSON - if body looks like a URL return it
                    val s = body.trim()
                    if (s.startsWith("http")) return s
                    return null
                }
            }
        } catch (_: Throwable) {
            return null
        }
    }

    // Public quick resolver: single-shot attempt with a short call timeout
    fun quickResolveStreamUrl(trackId: String, timeoutMs: Long = 1000L): String? {
        try {
            val clientShort = httpClient.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
            val url = "https://dab.yeet.su/api/stream?trackId=$trackId"
            val req = newRequestBuilder(url).build()
            clientShort.newCall(req).execute().use { resp ->
                val location = resp.header("Location")
                if (!location.isNullOrBlank()) return location
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                try {
                    val sr: DabStreamResponse = json.decodeFromString(body)
                    return sr.streamUrl ?: sr.url ?: sr.stream ?: sr.link
                } catch (_: Throwable) {
                    val s = body.trim()
                    if (s.startsWith("http")) return s
                    return null
                }
            }
        } catch (_: Throwable) {
            return null
        }
    }

    // Return a cached resolved stream URL if present and not expired
    fun getCachedStreamUrl(trackId: String?): String? {
        if (trackId == null) return null
        try {
            val entry = trackStreamCache[trackId] ?: return null
            if (System.currentTimeMillis() - entry.first <= STREAM_CACHE_TTL_MS) return entry.second
        } catch (_: Throwable) {}
        return null
    }

    // Public search helper used by the extension (synchronous)
    fun searchAll(q: String, trackLimit: Int, albumLimit: Int, artistLimit: Int): SearchResults {
        val totalLimit = (trackLimit + albumLimit + artistLimit).coerceAtLeast(8)
        val parsed = try { searchUnifiedSync(q, totalLimit) } catch (_: Throwable) { null }
        val tracks = parsed?.tracks?.take(trackLimit) ?: emptyList()
        val albums = parsed?.albums?.take(albumLimit) ?: emptyList()
        val artists = parsed?.artists?.take(artistLimit) ?: emptyList()
        return SearchResults(tracks, albums, artists)
    }

    // Artist helpers
    fun getArtistDetails(artistId: String): DabArtist? {
        try {
            val url = "https://dab.yeet.su/api/discography?artistId=${URLEncoder.encode(artistId, "UTF-8")}"
            val req = newRequestBuilder(url, includeCookie = false).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                try {
                    val d: DabDiscographyResponse = json.decodeFromString(body)
                    return d.artist
                } catch (_: Throwable) {
                    return null
                }
            }
        } catch (_: Throwable) {
            return null
        }
    }

    fun getArtistDiscography(artistId: String): List<DabAlbum> {
        try {
            val url = "https://dab.yeet.su/api/discography?artistId=${URLEncoder.encode(artistId, "UTF-8")}"
            val req = newRequestBuilder(url, includeCookie = false).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                try {
                    val d: DabDiscographyResponse = json.decodeFromString(body)
                    return d.albums
                } catch (_: Throwable) {
                    // Try to parse arrays as albums
                    try {
                        val root = json.parseToJsonElement(body)
                        if (root is JsonArray) return root.mapNotNull { el -> if (el is JsonObject) tryParseElementAsAlbum(el) else null }
                        if (root is JsonObject) {
                            val aArr = (root["albums"] as? JsonArray) ?: (root["results"] as? JsonArray)
                            if (aArr != null) return aArr.mapNotNull { el -> if (el is JsonObject) tryParseElementAsAlbum(el) else null }
                        }
                    } catch (_: Throwable) {}
                    return emptyList()
                }
            }
        } catch (_: Throwable) {
            return emptyList()
        }
    }

    // Synthesize lightweight Album objects from Track results when API doesn't return albums
    private fun synthesizeAlbumsFromTracks(tracks: List<dev.brahmkshatriya.echo.common.models.Track>, limit: Int): List<CommonAlbum> {
        val map = LinkedHashMap<String, CommonAlbum>()
        for (t in tracks) {
            val a = t.album ?: continue
            val id = a.id.ifBlank { a.title ?: continue }
            if (map.containsKey(id)) continue
            val album = CommonAlbum(
                id = id,
                title = a.title ?: "",
                cover = a.cover,
                artists = a.artists.ifEmpty { listOf() },
                releaseDate = a.date,
                trackCount = a.trackCount
            )
            map[id] = album
            if (map.size >= limit) break
        }
        return map.values.toList()
    }

    // Synthesize lightweight Artist objects from Track results when API doesn't return artists
    private fun synthesizeArtistsFromTracks(tracks: List<dev.brahmkshatriya.echo.common.models.Track>, limit: Int): List<CommonArtist> {
        val map = LinkedHashMap<String, CommonArtist>()
        for (t in tracks) {
            val artists = t.artists
            if (artists.isEmpty()) continue
            for (a in artists) {
                val id = a.id.ifBlank { a.name }
                if (map.containsKey(id)) continue
                val art = CommonArtist(id = id, name = a.name, cover = a.cover)
                map[id] = art
                if (map.size >= limit) break
            }
            if (map.size >= limit) break
        }
        return map.values.toList()
    }

    // Synchronous helpers (used by DABExtension). These keep the original API shape so callers
    // that use withContext(...){ api.method() } continue to work.

    fun fetchLibraryPlaylistsPage(page: Int = 1, pageSize: Int = 50): List<Playlist> {
        val url = "https://dab.yeet.su/api/libraries?limit=$pageSize&offset=${(page - 1) * pageSize}"
        return try {
            val response: DabPlaylistResponse = executeAuthenticated(url)
            response.libraries.map(converter::toPlaylist)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun getFavorites(limit: Int = 200, offset: Int = 0): List<Track> {
        val url = "https://dab.yeet.su/api/favorites?limit=$limit&offset=$offset"

        fun parseTracks(body: String): List<Track> {
            // Helpful debug: if body looks like an HTML error page, log a short snippet
            if (body.trimStart().startsWith("<")) {
                Log.w("DABApi", "getFavorites: received HTML body when expecting JSON; snippet=${body.trim().take(200).replace('\n',' ')}")
            }
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

            try {
                val trackResponse: DabTrackResponse = json.decodeFromString(body)
                return trackResponse.tracks.map(converter::toTrack)
            } catch (_: Exception) {
                // fallthrough
            }

            // Try to find any array of objects and decode elements as tracks
            try {
                val root = json.parseToJsonElement(body)
                val arrays = mutableListOf<JsonArray>()
                fun collectArrays(el: JsonElement) {
                    when (el) {
                        is JsonArray -> {
                            arrays.add(el)
                            el.forEach { collectArrays(it) }
                        }
                        is JsonObject -> el.values.forEach { collectArrays(it) }
                        else -> {}
                    }
                }
                collectArrays(root)
                for (arr in arrays) {
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
                                // ignore
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
                httpClient.newCall(req).execute().use { resp ->
                    val code = resp.code
                    val body = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        Log.w("DABApi", "getFavorites: request returned HTTP $code; bodySnippet=${body.trim().take(200).replace('\n',' ')}")
                        return emptyList()
                    }
                    val parsed = parseTracks(body)
                    if (parsed.isEmpty()) {
                        Log.d("DABApi", "getFavorites: parsed 0 tracks from response; bodySnippet=${body.trim().take(200).replace('\n',' ')}")
                    }
                    return parsed
                }
             } catch (_: Throwable) {
                 emptyList()
             }
         }

        // 1) Try with normalized cookie (session=...)
        val cookieHeader = cookieHeaderValue()
        if (!cookieHeader.isNullOrBlank()) {
            val res = tryRequestWithBuilder(Request.Builder().url(url).header("Cookie", cookieHeader))
            if (res.isNotEmpty()) return res
        }

        // 2) Try Authorization bearer extracted from raw saved cookie
        val raw = settings.getString("session_cookie")
        val token = when {
            raw.isNullOrBlank() -> null
            raw.contains('=') -> raw.substringAfter('=')
            else -> raw
        }

        if (!token.isNullOrBlank()) {
            val resAuth = tryRequestWithBuilder(Request.Builder().url(url).header("Authorization", "Bearer $token"))
            if (resAuth.isNotEmpty()) return resAuth

            val alternates = listOf("token", "auth", "session_id")
            for (name in alternates) {
                val ch = "$name=$token"
                val r = tryRequestWithBuilder(Request.Builder().url(url).header("Cookie", ch))
                if (r.isNotEmpty()) return r
            }
        }

        // 3) Fallback: try unauthenticated
        return tryRequestWithBuilder(Request.Builder().url(url))
    }

    fun fetchFavoritesPage(pageIndex: Int = 1, pageSize: Int = 200): List<Track> {
        val limit = pageSize
        val offset = (pageIndex - 1) * pageSize
        return getFavorites(limit, offset)
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

    // Try a single /api/search call that returns mixed results to reduce round-trips.
    private fun tryParseElementAsTrack(el: JsonElement): DabTrack? {
        return try {
            json.decodeFromJsonElement(DabTrack.serializer(), el)
        } catch (_: Throwable) { null }
    }

    private fun tryParseElementAsAlbum(el: JsonElement): DabAlbum? {
        return try {
            json.decodeFromJsonElement(DabAlbum.serializer(), el)
        } catch (_: Throwable) { null }
    }

    private fun tryParseElementAsArtist(el: JsonElement): DabArtist? {
        return try {
            json.decodeFromJsonElement(DabArtist.serializer(), el)
        } catch (_: Throwable) { null }
    }

    private fun searchUnifiedSync(q: String, totalLimit: Int): SearchResults? {
        // synchronous helper used from suspend function
        val url = "https://dab.yeet.su/api/search?q=${URLEncoder.encode(q, "UTF-8")}&limit=$totalLimit&offset=0"
        try {
            val req = newRequestBuilder(url, includeCookie = false).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val root = json.parseToJsonElement(body)

                // Support multiple result shapes:
                // 1) a bare array of mixed objects
                // 2) { tracks: [...], albums: [...], artists: [...] }
                // 3) { results: [...] } or other top-level array fields
                val tracks = mutableListOf<Track>()
                val albums = mutableListOf<CommonAlbum>()
                val artists = mutableListOf<CommonArtist>()

                if (root is JsonArray) {
                    for (el in root) {
                        if (el !is JsonObject) continue
                        tryParseElementAsTrack(el)?.let { tracks.add(converter.toTrack(it)); continue }
                        tryParseElementAsAlbum(el)?.let { albums.add(converter.toAlbum(it)); continue }
                        tryParseElementAsArtist(el)?.let { artists.add(converter.toArtist(it)); continue }
                    }
                    // Debug: if we parsed only tracks, log a snippet of the raw body to help diagnose server shapes
                    if (tracks.isNotEmpty() && albums.isEmpty() && artists.isEmpty()) {
                        try {
                            Log.d("DABApi", "searchUnifiedSync: parsed only tracks from mixed array response for q=$q; bodySnippet=${body.trim().take(800).replace('\n',' ')}")
                        } catch (_: Throwable) {}
                    }
                    return SearchResults(tracks, albums, artists)
                }

                if (root is JsonObject) {
                    // direct typed arrays
                    val tArr = (root["tracks"] as? JsonArray) ?: (root["track"] as? JsonArray)
                    val aArr = (root["albums"] as? JsonArray) ?: (root["album"] as? JsonArray)
                    val arArr = (root["artists"] as? JsonArray) ?: (root["artist"] as? JsonArray)

                    if (tArr != null || aArr != null || arArr != null) {
                        tArr?.forEach { if (it is JsonObject) tryParseElementAsTrack(it)?.let { tracks.add(converter.toTrack(it)) } }
                        aArr?.forEach { if (it is JsonObject) tryParseElementAsAlbum(it)?.let { albums.add(converter.toAlbum(it)) } }
                        arArr?.forEach { if (it is JsonObject) tryParseElementAsArtist(it)?.let { artists.add(converter.toArtist(it)) } }
                        return SearchResults(tracks, albums, artists)
                    }

                    // scan any top-level arrays as mixed results
                    for ((_, v) in root) {
                        if (v is JsonArray) {
                            for (el in v) {
                                if (el !is JsonObject) continue
                                tryParseElementAsTrack(el)?.let { tracks.add(converter.toTrack(it)); continue }
                                tryParseElementAsAlbum(el)?.let { albums.add(converter.toAlbum(it)); continue }
                                tryParseElementAsArtist(el)?.let { artists.add(converter.toArtist(it)); continue }
                            }
                        }
                    }

                    // final fallback: explicit "results"
                    val resultsArr = root["results"] as? JsonArray
                    if (resultsArr != null) {
                        for (el in resultsArr) {
                            if (el !is JsonObject) continue
                            tryParseElementAsTrack(el)?.let { tracks.add(converter.toTrack(it)); continue }
                            tryParseElementAsAlbum(el)?.let { albums.add(converter.toAlbum(it)); continue }
                            tryParseElementAsArtist(el)?.let { artists.add(converter.toArtist(it)); continue }
                        }
                    }
                }

                return SearchResults(tracks, albums, artists)
            }
        } catch (_: Throwable) {
            return null
        }
    }

    private suspend fun searchUnified(q: String, trackLimit: Int, albumLimit: Int, artistLimit: Int): SearchResults? {
        val totalLimit = (trackLimit + albumLimit + artistLimit).coerceAtLeast(8)
        return withContext(Dispatchers.IO) {
            val parsed = searchUnifiedSync(q, totalLimit) ?: return@withContext null
            val tracks = parsed.tracks.take(trackLimit)
            val albums = parsed.albums.take(albumLimit)
            val artists = parsed.artists.take(artistLimit)
            SearchResults(tracks, albums, artists)
        }
    }

    // Simple typed-track search helper
    fun searchTracks(query: String, limit: Int = 8): List<Track> {
        val url = "https://dab.yeet.su/api/search?q=${URLEncoder.encode(query, "UTF-8")}&type=track&limit=$limit&offset=0"
        val req = newRequestBuilder(url, includeCookie = false).build()
        return try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val parsed = try {
                    val tr: DabTrackResponse = json.decodeFromString(body)
                    tr.tracks.map(converter::toTrack)
                } catch (_: Throwable) {
                    // fallthrough - try parsing mixed shapes
                    try {
                        val root = json.parseToJsonElement(body)
                        if (root is JsonArray) {
                            root.mapNotNull { el -> if (el is JsonObject) tryParseElementAsTrack(el)?.let(converter::toTrack) else null }
                        } else {
                            emptyList()
                        }
                    } catch (_: Throwable) { emptyList() }
                }

                if (parsed.isNotEmpty()) return parsed

                // retry including cookie in case server requires it
                try {
                    val req2 = newRequestBuilder(url, includeCookie = true).build()
                    httpClient.newCall(req2).execute().use { r2 ->
                        if (!r2.isSuccessful) return emptyList()
                        val body2 = r2.body?.string() ?: return emptyList()
                        try {
                            val tr2: DabTrackResponse = json.decodeFromString(body2)
                            val mapped2 = tr2.tracks.map(converter::toTrack)
                            if (mapped2.isNotEmpty()) return mapped2
                        } catch (_: Throwable) {}

                        try {
                            val root2 = json.parseToJsonElement(body2)
                            if (root2 is JsonArray) return root2.mapNotNull { el -> if (el is JsonObject) tryParseElementAsTrack(el)?.let(converter::toTrack) else null }
                        } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}

                emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // Simple album search that prefers a typed album search endpoint
    fun searchAlbums(query: String, limit: Int = 4): List<CommonAlbum> {
        // Prefer the typed search helper which is more robust
        val byType = try { fetchAlbumsByTypeSearch(query, limit) } catch (_: Throwable) { emptyList<CommonAlbum>() }
        if (byType.isNotEmpty()) return byType

        // Fallback to calling generic albums endpoint
        val url = "https://dab.yeet.su/api/search?q=${URLEncoder.encode(query, "UTF-8")}&type=album&limit=$limit&offset=0"
        val req = newRequestBuilder(url, includeCookie = false).build()
        return try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                try {
                    val albumResponse: DabAlbumResponse = json.decodeFromString(body)
                    val mapped = albumResponse.albums.map(converter::toAlbum)
                    if (mapped.isNotEmpty()) return mapped
                } catch (_: Throwable) {}

                try {
                    val root = json.parseToJsonElement(body)
                    if (root is JsonArray) {
                        val parsed = root.mapNotNull { el -> if (el is JsonObject) tryParseElementAsAlbum(el)?.let(converter::toAlbum) else null }
                        if (parsed.isNotEmpty()) return parsed
                    }
                } catch (_: Throwable) {}

                // try with cookie
                try {
                    val req2 = newRequestBuilder(url, includeCookie = true).build()
                    httpClient.newCall(req2).execute().use { r2 ->
                        if (!r2.isSuccessful) return emptyList()
                        val body2 = r2.body?.string() ?: return emptyList()
                        try {
                            val albumResponse2: DabAlbumResponse = json.decodeFromString(body2)
                            val mapped2 = albumResponse2.albums.map(converter::toAlbum)
                            if (mapped2.isNotEmpty()) return mapped2
                        } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}

                emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun fetchAlbumsByTypeSearch(q: String, limit: Int): List<CommonAlbum> {
        val url = "https://dab.yeet.su/api/search?q=${URLEncoder.encode(q, "UTF-8")}&type=album&limit=$limit&offset=0"
        val req = newRequestBuilder(url, includeCookie = false).build()
        return try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                // Try well-known typed response first
                try {
                    val albumResponse: DabAlbumResponse = json.decodeFromString(body)
                    return albumResponse.albums.map(converter::toAlbum)
                } catch (_: Throwable) {
                    // fallthrough
                }

                // Parse JSON and look for arrays of albums or mixed results
                try {
                    val root = json.parseToJsonElement(body)
                    if (root is JsonArray) {
                        val parsed = root.mapNotNull { el -> if (el is JsonObject) tryParseElementAsAlbum(el)?.let(converter::toAlbum) else null }
                        if (parsed.isNotEmpty()) return parsed
                    }

                    if (root is JsonObject) {
                        val aArr = (root["albums"] as? JsonArray) ?: (root["results"] as? JsonArray)
                        if (aArr != null) {
                            val parsed = aArr.mapNotNull { el -> if (el is JsonObject) tryParseElementAsAlbum(el)?.let(converter::toAlbum) else null }
                            if (parsed.isNotEmpty()) return parsed
                        }

                        for ((_, v) in root) {
                            if (v is JsonArray) {
                                val parsed = v.mapNotNull { el -> if (el is JsonObject) tryParseElementAsAlbum(el)?.let(converter::toAlbum) else null }
                                if (parsed.isNotEmpty()) return parsed
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // ignore
                }

                emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun fetchArtistsByTypeSearch(q: String, limit: Int): List<CommonArtist> {
        val url = "https://dab.yeet.su/api/search?q=${URLEncoder.encode(q, "UTF-8")}&type=artist&limit=$limit&offset=0"
        val req = newRequestBuilder(url, includeCookie = false).build()
        return try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                try {
                    val artistResponse: DabArtistResponse = json.decodeFromString(body)
                    return artistResponse.artists.map(converter::toArtist)
                } catch (_: Throwable) {
                    // fallthrough
                }

                try {
                    val root = json.parseToJsonElement(body)
                    if (root is JsonArray) {
                        val parsed = root.mapNotNull { el -> if (el is JsonObject) tryParseElementAsArtist(el)?.let(converter::toArtist) else null }
                        if (parsed.isNotEmpty()) return parsed
                    }

                    if (root is JsonObject) {
                        val arArr = (root["artists"] as? JsonArray) ?: (root["results"] as? JsonArray)
                        if (arArr != null) {
                            val parsed = arArr.mapNotNull { el -> if (el is JsonObject) tryParseElementAsArtist(el)?.let(converter::toArtist) else null }
                            if (parsed.isNotEmpty()) return parsed
                        }

                        for ((_, v) in root) {
                            if (v is JsonArray) {
                                val parsed = v.mapNotNull { el -> if (el is JsonObject) tryParseElementAsArtist(el)?.let(converter::toArtist) else null }
                                if (parsed.isNotEmpty()) return parsed
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // ignore
                }

                emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // Public helper: fetch a single album by id. Tries /album/{id} or /album?albumId=...
    fun getAlbum(albumId: String): DabAlbum? {
        try {
            val url = "https://dab.yeet.su/api/album/${URLEncoder.encode(albumId, "UTF-8") }"
            val req = newRequestBuilder(url, includeCookie = false).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                try {
                    // Try single-album shaped response first
                    val single: DabSingleAlbumResponse = json.decodeFromString(body)
                    return single.album
                } catch (_: Throwable) {
                    // fallthrough - try to decode as array/object
                }

                try {
                    val root = json.parseToJsonElement(body)
                    if (root is JsonObject) {
                        val albumObj = (root["album"] as? JsonObject) ?: root
                        return tryParseElementAsAlbum(albumObj)
                    }
                    if (root is JsonArray && root.isNotEmpty()) {
                        val first = root.first()
                        if (first is JsonObject) return tryParseElementAsAlbum(first)
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return null
    }

    // Public helper: fetch lyrics via /lyrics?artist=..&title=..
    fun getLyrics(artist: String, title: String): String? {
        try {
            val url = "https://dab.yeet.su/api/lyrics?artist=${URLEncoder.encode(artist, "UTF-8")}&title=${URLEncoder.encode(title, "UTF-8") }"
            val req = newRequestBuilder(url, includeCookie = false).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                try {
                    val dr: DabLyricsResponse = json.decodeFromString(body)
                    return dr.lyrics
                } catch (_: Throwable) {
                    // if body is plain text, return it
                    val s = body.trim()
                    if (s.isNotEmpty()) return s
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    // Resolve a trackId to a concrete stream URL using cache and coalesced in-flight requests.
    fun getStreamUrl(trackId: String): String? {
        if (trackId.isBlank()) return null

        // Fast path: in-memory cache
        getCachedStreamUrl(trackId)?.let { return it }

        // Coalesce concurrent lookups using putIfAbsent to avoid experimental APIs
        val newDeferred = CompletableDeferred<String>()
        val existing = pendingStreamRequests.putIfAbsent(trackId, newDeferred)

        if (existing != null) {
            // Another lookup in-flight; wait for it
            return try {
                runBlocking { existing.await() }
            } catch (_: Throwable) { null }
        }

        // We created the deferred; perform resolution synchronously and complete it
        try {
            var resolved: String? = quickResolveStreamUrl(trackId)

            if (resolved.isNullOrBlank()) {
                try {
                    val url = "https://dab.yeet.su/api/stream?trackId=${URLEncoder.encode(trackId, "UTF-8") }"
                    val req = newRequestBuilder(url).build()
                    httpClient.newCall(req).execute().use { resp ->
                        val loc = resp.header("Location")
                        if (!loc.isNullOrBlank()) resolved = loc
                        else if (resp.isSuccessful) {
                            val body = resp.body?.string() ?: ""
                            try {
                                val sr: DabStreamResponse = json.decodeFromString(body)
                                resolved = sr.streamUrl ?: sr.url ?: sr.stream ?: sr.link
                            } catch (_: Throwable) {
                                val s = body.trim()
                                if (s.startsWith("http")) resolved = s
                            }
                        }
                    }
                } catch (_: Throwable) { /* ignore */ }
            }

            if (!resolved.isNullOrBlank()) {
                try {
                    val ts = System.currentTimeMillis()
                    trackStreamCache[trackId] = ts to resolved
                    persistStreamCacheEntry(trackId, resolved)
                } catch (_: Throwable) {}
                newDeferred.complete(resolved)
            } else {
                newDeferred.completeExceptionally(IllegalStateException("Could not resolve stream for $trackId"))
            }
        } catch (e: Throwable) {
            newDeferred.completeExceptionally(e)
        } finally {
            pendingStreamRequests.remove(trackId)
        }

        return try {
            runBlocking { newDeferred.await() }
        } catch (_: Throwable) { null }
    }
}
