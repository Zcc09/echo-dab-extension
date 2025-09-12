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

    fun getCachedTracksForPlaylist(playlistId: String): List<Track>? {
        val entry = playlistCache[playlistId] ?: return null
        return if (System.currentTimeMillis() - entry.first <= CACHE_TTL_MS) entry.second else null
    }

    suspend fun fetchTracksForPlaylistSync(playlist: Playlist, pageIndex: Int = 1, pageSize: Int = 1000): List<Track> {
        return withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cacheEntry = playlistCache[playlist.id]
            if (cacheEntry != null && now - cacheEntry.first <= CACHE_TTL_MS) {
                return@withContext cacheEntry.second
            }

            val candidateUrls = listOf(
                "https://dab.yeet.su/api/libraries/${URLEncoder.encode(playlist.id, "UTF-8")}",
                "https://dab.yeet.su/api/libraries/${URLEncoder.encode(playlist.id, "UTF-8")}/tracks",
                "https://dab.yeet.su/api/playlists/${URLEncoder.encode(playlist.id, "UTF-8")}/tracks",
                "https://dab.yeet.su/api/playlists/${URLEncoder.encode(playlist.id, "UTF-8")}",
                "https://dab.yeet.su/api/libraries/${URLEncoder.encode(playlist.id, "UTF-8")}/items"
            )

            val clientShort = httpClient.newBuilder().callTimeout(3000, TimeUnit.MILLISECONDS).build()

            for (url in candidateUrls) {
                try {
                    val requestUrl = if (url.contains('?')) "$url&page=$pageIndex&limit=$pageSize" else "$url?page=$pageIndex&limit=$pageSize"
                    val rb = okhttp3.Request.Builder().url(requestUrl)
                    val cookie = cookieHeaderValue()
                    if (!cookie.isNullOrBlank()) rb.header("Cookie", cookie)
                    rb.header("Accept", "application/json").header("User-Agent", "EchoDAB-Extension/1.0")

                    clientShort.newCall(rb.build()).execute().use { resp ->
                        if (!resp.isSuccessful) continue
                        val body = resp.body?.string() ?: continue

                        val tracks = parseTracksFromBody(body)
                        if (tracks.isNotEmpty()) {
                            playlistCache[playlist.id] = now to tracks
                            Log.d("PlaylistService", "Found ${tracks.size} tracks for playlist=${playlist.id} from=$requestUrl")
                            return@withContext tracks
                        }
                    }
                } catch (_: Throwable) { }
            }

            Log.d("PlaylistService", "No tracks found for playlist=${playlist.id}")
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

        // Try generic JSON parsing
        try {
            val root = json.parseToJsonElement(body)
            return parseTracksFromJsonElement(root)
        } catch (_: Throwable) { }

        return emptyList()
    }

    private fun parseTracksFromJsonElement(root: kotlinx.serialization.json.JsonElement): List<Track> {
        val out = mutableListOf<Track>()

        when (root) {
            is JsonObject -> {
                val candidates = listOf("tracks", "data", "items", "results", "favorites")
                for (key in candidates) {
                    val arr = root[key] as? JsonArray
                    if (arr != null) {
                        for (el in arr) {
                            if (el is JsonObject) {
                                try {
                                    val dt = json.decodeFromJsonElement(dev.brahmkshatriya.echo.extension.models.DabTrack.serializer(), el)
                                    out.add(converter.toTrack(dt))
                                } catch (_: Throwable) { }
                            }
                        }
                        if (out.isNotEmpty()) break
                    }
                }
            }
            is JsonArray -> {
                for (item in root) {
                    if (item is JsonObject) {
                        try {
                            val dt = json.decodeFromJsonElement(dev.brahmkshatriya.echo.extension.models.DabTrack.serializer(), item)
                            out.add(converter.toTrack(dt))
                        } catch (_: Throwable) { }
                    }
                }
            }
        }
        return out
    }

    fun fetchLibraryPlaylistsPage(pageIndex: Int = 1, pageSize: Int = 50, includeCovers: Boolean = true): List<Playlist> {
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
                    if (!resp.isSuccessful) continue
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
