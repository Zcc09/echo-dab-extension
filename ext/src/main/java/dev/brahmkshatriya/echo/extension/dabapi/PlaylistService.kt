package dev.brahmkshatriya.echo.extension.dabapi

import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Artist
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import okhttp3.OkHttpClient
import dev.brahmkshatriya.echo.common.settings.Settings
import java.util.concurrent.TimeUnit
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.brahmkshatriya.echo.extension.Converter
import kotlinx.coroutines.runBlocking

class PlaylistService(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val converter: Converter,
    private val settings: Settings
) {
    private val playlistCache = mutableMapOf<String, Pair<Long, List<Track>>>()
    private val CACHE_TTL_MS = 2 * 60 * 1000L
    private var playlistsCache: Pair<Long, List<Playlist>>? = null
    private val PLAYLISTS_CACHE_TTL_MS = 60 * 1000L

    private fun cookieHeaderValue(): String? {
        val raw = settings.getString("session_cookie") ?: return null
        val firstPart = raw.split(';').firstOrNull()?.trim() ?: return null
        if (firstPart.isEmpty()) return null
        return if (firstPart.contains('=')) firstPart else "session=$firstPart"
    }

    // Check if user is logged in (has valid session cookie)
    private fun isLoggedIn(): Boolean {
        return !cookieHeaderValue().isNullOrBlank()
    }

    fun getCachedTracksForPlaylist(playlistId: String): List<Track>? {
        // Only return cached data if user is still logged in
        if (!isLoggedIn()) {
            playlistCache.clear() // Clear cache when logged out
            return null
        }

        val entry = playlistCache[playlistId] ?: return null
        return if (System.currentTimeMillis() - entry.first <= CACHE_TTL_MS) entry.second else null
    }

    suspend fun fetchTracksForPlaylistSync(playlist: Playlist, pageIndex: Int = 1, pageSize: Int = 1000): List<Track> {
        return withContext(Dispatchers.IO) {
            // Require authentication for playlists
            if (!isLoggedIn()) {
                playlistCache.clear()
                Log.d("PlaylistService", "Not logged in, returning empty tracks for playlist=${playlist.id}")
                return@withContext emptyList()
            }

            val now = System.currentTimeMillis()
            val cacheEntry = playlistCache[playlist.id]
            if (cacheEntry != null && now - cacheEntry.first <= CACHE_TTL_MS) {
                Log.d("PlaylistService", "Returning cached ${cacheEntry.second.size} tracks for playlist=${playlist.id}")
                return@withContext cacheEntry.second
            }

            Log.d("PlaylistService", "Fetching tracks for playlist=${playlist.id}, title='${playlist.title}'")

            // More comprehensive endpoint candidates with better prioritization
            val candidateUrls = listOf(
                // Try the most likely endpoints first
                "https://dab.yeet.su/api/libraries/${URLEncoder.encode(playlist.id, "UTF-8")}/tracks",
                "https://dab.yeet.su/api/playlists/${URLEncoder.encode(playlist.id, "UTF-8")}/tracks",
                "https://dab.yeet.su/api/libraries/${URLEncoder.encode(playlist.id, "UTF-8")}",
                "https://dab.yeet.su/api/playlists/${URLEncoder.encode(playlist.id, "UTF-8")}",
                "https://dab.yeet.su/api/libraries/${URLEncoder.encode(playlist.id, "UTF-8")}/items",
                // Additional fallbacks
                "https://dab.yeet.su/api/library/${URLEncoder.encode(playlist.id, "UTF-8")}/tracks",
                "https://dab.yeet.su/api/playlist/${URLEncoder.encode(playlist.id, "UTF-8")}/tracks"
            )

            // Use longer timeout for more reliable fetching
            val clientRobust = httpClient.newBuilder().callTimeout(10000, TimeUnit.MILLISECONDS).build()

            for ((index, url) in candidateUrls.withIndex()) {
                try {
                    val requestUrl = if (url.contains('?')) "$url&page=$pageIndex&limit=$pageSize" else "$url?page=$pageIndex&limit=$pageSize"
                    Log.d("PlaylistService", "Trying endpoint ${index + 1}/${candidateUrls.size}: $requestUrl")

                    val rb = okhttp3.Request.Builder().url(requestUrl)
                    val cookie = cookieHeaderValue()
                    if (!cookie.isNullOrBlank()) rb.header("Cookie", cookie)
                    rb.header("Accept", "application/json").header("User-Agent", "EchoDAB-Extension/1.0")

                    clientRobust.newCall(rb.build()).execute().use { resp ->
                        Log.d("PlaylistService", "Response: ${resp.code} ${resp.message} for $requestUrl")

                        if (!resp.isSuccessful) {
                            // Clear invalid session on auth errors
                            if (resp.code == 401 || resp.code == 403) {
                                Log.w("PlaylistService", "Authentication failed, clearing session")
                                settings.putString("session_cookie", null)
                                playlistCache.clear()
                                playlistsCache = null
                            }
                            Log.d("PlaylistService", "Request failed with ${resp.code}, trying next endpoint")
                            continue
                        }

                        val body = resp.body?.string()
                        if (body.isNullOrEmpty()) {
                            Log.d("PlaylistService", "Empty response body, trying next endpoint")
                            continue
                        }

                        Log.d("PlaylistService", "Response body length: ${body.length} chars")
                        Log.v("PlaylistService", "Response body preview: ${body.take(200)}...")

                        val tracks = parseTracksFromBody(body)
                        Log.d("PlaylistService", "Parsed ${tracks.size} tracks from response")

                        if (tracks.isNotEmpty()) {
                            playlistCache[playlist.id] = now to tracks
                            Log.i("PlaylistService", "SUCCESS: Found ${tracks.size} tracks for playlist=${playlist.id} from=$requestUrl")
                            return@withContext tracks
                        } else {
                            Log.d("PlaylistService", "No tracks found in response, trying next endpoint")
                        }
                    }
                } catch (e: Throwable) {
                    Log.w("PlaylistService", "Exception trying endpoint $url: ${e.message}")
                }
            }

            Log.w("PlaylistService", "FAILED: No tracks found for playlist=${playlist.id} after trying all endpoints")
            playlistCache[playlist.id] = now to emptyList()
            emptyList()
        }
    }

    private fun parseTracksFromBody(body: String): List<Track> {
        // Try typed decode first
        try {
            val lib: dev.brahmkshatriya.echo.extension.models.DabLibrary = json.decodeFromString(body)
            return lib.tracks.mapNotNull { t -> try { converter.toTrack(t) } catch (_: Throwable) { null } }
        } catch (_: Throwable) { }

        // Use enhanced JSON parsing (consolidated from ParserService)
        try {
            val root = json.parseToJsonElement(body)
            return parseTracksFromJsonElement(root)
        } catch (_: Throwable) { }

        return emptyList()
    }

    // Enhanced JSON element parsing (integrated from ParserService, removing redundancy)
    private fun parseTracksFromJsonElement(root: kotlinx.serialization.json.JsonElement): List<Track> {
        val out = mutableListOf<Track>()

        fun visit(el: kotlinx.serialization.json.JsonElement) {
            when (el) {
                is JsonObject -> {
                    // Try direct track object parsing
                    val direct = tryParseElementAsTrack(el)
                    if (direct != null) {
                        out.add(converter.toTrack(direct))
                        return
                    }

                    // Check common array containers with expanded candidates
                    val candidates = listOf("tracks", "data", "items", "results", "favorites", "library", "content")
                    for (key in candidates) {
                        val arr = el[key] as? JsonArray
                        if (arr != null) {
                            for (item in arr) {
                                if (item is JsonObject) {
                                    val parsed = tryParseElementAsTrack(item)
                                    if (parsed != null) out.add(converter.toTrack(parsed))
                                }
                            }
                            if (out.isNotEmpty()) return
                        }
                    }
                }
                is JsonArray -> {
                    for (item in el) {
                        if (item is JsonObject) {
                            val parsed = tryParseElementAsTrack(item)
                            if (parsed != null) out.add(converter.toTrack(parsed))
                        }
                    }
                }
                else -> { /* Handle other types if needed */ }
            }
        }

        try { visit(root) } catch (_: Throwable) { }
        return out
    }

    // Enhanced track parsing (removing duplicate logic)
    private fun tryParseElementAsTrack(el: JsonObject): dev.brahmkshatriya.echo.extension.models.DabTrack? {
        return try {
            json.decodeFromJsonElement(dev.brahmkshatriya.echo.extension.models.DabTrack.serializer(), el)
        } catch (_: Throwable) {
            // Enhanced manual fallback with more field variations
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

            dev.brahmkshatriya.echo.extension.models.DabTrack(
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
        }
    }

    fun fetchLibraryPlaylistsPage(pageIndex: Int = 1, pageSize: Int = 50, includeCovers: Boolean = true): List<Playlist> {
        // Require authentication for playlists
        if (!isLoggedIn()) {
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
                val rb = okhttp3.Request.Builder().url(url)
                val cookie = cookieHeaderValue()
                if (!cookie.isNullOrBlank()) rb.header("Cookie", cookie)
                rb.header("Accept", "application/json").header("User-Agent", "EchoDAB-Extension/1.0")

                httpClient.newCall(rb.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        // Clear invalid session on auth errors
                        if (resp.code == 401 || resp.code == 403) {
                            settings.putString("session_cookie", null)
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

    private fun parsePlaylistsFromBody(body: String): List<Playlist> {
        try {
            val root = json.parseToJsonElement(body)
            val list = mutableListOf<Playlist>()

            val arr = when (root) {
                is JsonArray -> root
                is JsonObject -> {
                    val keys = listOf("libraries", "playlists", "data", "items", "results")
                    keys.firstNotNullOfOrNull { root[it] as? JsonArray }
                }
                else -> null
            }

            arr?.forEach { el ->
                if (el is JsonObject) {
                    try {
                        val dp = json.decodeFromJsonElement(dev.brahmkshatriya.echo.extension.models.DabPlaylist.serializer(), el)
                        list.add(converter.toPlaylist(dp))
                    } catch (_: Throwable) {
                        // Manual parsing fallback
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

    private fun extractStringValue(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val value = (obj[key] as? JsonPrimitive)?.content
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

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
                    Log.d("PlaylistService", "Set playlist=${p.id} cover from track")
                }
            } catch (_: Throwable) { }
        }
    }
}
