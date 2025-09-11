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

import java.util.Locale
class DABApi(
    private val httpClient: OkHttpClient,
    private val converter: Converter,
    private val settings: Settings
) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

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
        val sessionCookie = settings.getString("session_cookie")
            ?: error("Not logged in: session cookie not found.")

        val request = Request.Builder()
            .url(url)
            .header("Cookie", sessionCookie)
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
        val sessionCookie = settings.getString("session_cookie")
        val url = "https://dab.yeet.su/api/stream?trackId=$trackId"
        val requestBuilder = Request.Builder().url(url)
        if (sessionCookie != null) {
            requestBuilder.header("Cookie", sessionCookie)
        }
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

    fun getLibraryPlaylists(page: Int, pageSize: Int): PagedData<Playlist> {
        return PagedData.Continuous { continuation ->
            val pageNum = continuation?.toIntOrNull() ?: page
            val url = "https://dab.yeet.su/api/libraries?limit=$pageSize&offset=${(pageNum - 1) * pageSize}"
            val response: DabPlaylistResponse = executeAuthenticated(url)
            val playlists = response.libraries.map(converter::toPlaylist)
            Page(playlists, if (playlists.isNotEmpty()) (pageNum + 1).toString() else null)
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

    fun getAlbum(albumId: String): DabAlbum? {
        val url = "https://dab.yeet.su/api/album/$albumId"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return null
        val body = response.body?.string() ?: return null

        return try {
            val albumResponse: DabSingleAlbumResponse = json.decodeFromString(body)
            albumResponse.album
        } catch (_: Exception) {
            null
        }
    }

    fun getLyrics(artist: String, title: String): String? {
        // Request lyrics from API
        val encodedArtist = URLEncoder.encode(artist, "UTF-8")
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val url = "https://dab.yeet.su/api/lyrics?artist=$encodedArtist&title=$encodedTitle"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return null
        val body = response.body?.string() ?: return null
        return try {
            val lyricsResponse: DabLyricsResponse = json.decodeFromString(body)
            lyricsResponse.lyrics
        } catch (e: Exception) {
            // Fallback: try other JSON shapes or plain text
             // Try to handle other JSON shapes: array of lines or object with array
             try {
                 val elem = json.parseToJsonElement(body)
                 when (elem) {
                     is JsonArray -> {
                         val lrc = buildLrcFromJsonArray(elem)
                         return lrc.takeIf { it.isNotBlank() }
                     }
                     is JsonObject -> {
                         // Look for common array fields
                         val arr = when {
                             elem["lyrics"] is JsonArray -> elem["lyrics"] as JsonArray
                             elem["lines"] is JsonArray -> elem["lines"] as JsonArray
                             else -> null
                         }
                         if (arr != null) {
                             val lrc = buildLrcFromJsonArray(arr)
                             return lrc.takeIf { it.isNotBlank() }
                         }
                     }
                     else -> { /* fallthrough */ }
                 }
             } catch (_: Exception) {
                 // ignore parse error
             }

             // If it's not JSON or conversion failed, try stripping HTML and return plain text
            val stripped = stripHtml(body).trim()
            stripped.takeIf { it.isNotBlank() }
         }
     }

    private fun buildLrcFromJsonArray(arr: JsonArray): String {
        val sb = StringBuilder()
        for (el in arr) {
            when (el) {
                is JsonPrimitive -> {
                    val line = el.content.trim()
                    if (line.isNotEmpty()) sb.append(line).append('\n')
                }
                is JsonObject -> {
                    // Look for typical fields
                    val timeEl = el["time"] ?: el["timestamp"] ?: el["start"] ?: el["t"]
                    val textEl = el["text"] ?: el["line"] ?: el["lyrics"] ?: el["content"]

                    val timeStr = when (timeEl) {
                        is JsonPrimitive -> timeEl.content
                        else -> null
                    }

                    val text = when (textEl) {
                        is JsonPrimitive -> textEl.content
                        is JsonArray -> textEl.joinToString(" ") { (it as? JsonPrimitive)?.content ?: "" }
                        is JsonObject -> textEl.values.filterIsInstance<JsonPrimitive>().joinToString(" ") { it.content }
                        else -> el.values.filterIsInstance<JsonPrimitive>().joinToString(" ") { it.content }
                    }.trim()

                    val timeTag = timeStr?.let { parseTimeToLrc(it) }
                    if (timeTag != null) sb.append("[$timeTag]")
                    if (text.isNotEmpty()) sb.append(text)
                    sb.append('\n')
                }
                else -> {
                    // ignore other types
                }
            }
        }
        return sb.toString().trim()
    }

    private fun parseTimeToLrc(time: String): String? {
        val trimmed = time.trim()
        // If numeric (seconds) -> convert
        val asDouble = trimmed.toDoubleOrNull()
        if (asDouble != null) {
            val totalMs = (asDouble * 1000).toLong()
            return msToLrcTime(totalMs)
        }
        // If already in mm:ss or mm:ss.xxx form, normalize milliseconds to 3 digits
        val mmssRegex = Regex("^(\\d+):(\\d{1,2})(?:[.:](\\d{1,3}))?$")
        val m = mmssRegex.matchEntire(trimmed)
        if (m != null) {
            val mm = m.groupValues[1].toLong()
            val ss = m.groupValues[2].toLong()
            val msPart = m.groupValues.getOrNull(3) ?: "0"
            val ms = when (msPart.length) {
                0 -> 0
                1 -> (msPart.toLong() * 100)
                2 -> (msPart.toLong() * 10)
                else -> msPart.padEnd(3, '0').take(3).toLong()
            }
            val totalMs = mm * 60_000 + ss * 1000 + ms
            return msToLrcTime(totalMs)
        }
        return null
    }

    private fun msToLrcTime(ms: Long): String {
        val minutes = ms / 60_000
        val seconds = (ms % 60_000) / 1000
        val millis = (ms % 1000)
        return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
    }

    private fun stripHtml(input: String): String {
        // Very small heuristic HTML stripper: removes tags and unescapes basic entities
        val noTags = input.replace(Regex("<[^>]+>"), "")
        return noTags
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", '"'.toString())
            .replace("&#39;", "'")
    }

}