package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.models.DabLoginRequest
import dev.brahmkshatriya.echo.extension.models.DabPlaylistResponse
import dev.brahmkshatriya.echo.extension.models.DabTrackResponse
import dev.brahmkshatriya.echo.extension.models.DabUserResponse
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

class DABApi(
    private val httpClient: OkHttpClient,
    private val converter: Converter,
    private val settings: Settings
) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    // A robust in-memory CookieJar to handle Java/Kotlin interop correctly.
    private val cookieJar = object : CookieJar {
        private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            // Defensively convert to a new Kotlin list to ensure type compatibility.
            return cookieStore[url.host]?.toList() ?: emptyList()
        }
    }

    private val client = httpClient.newBuilder().cookieJar(cookieJar).build()

    private inline fun <reified T> execute(request: Request): T {
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: error("Empty response body")
        if (!response.isSuccessful) {
            error("API Error: ${response.code} ${response.message} - $body")
        }
        return json.decodeFromString(body)
    }

    fun login(email: String, pass: String): User {
        val loginRequest = DabLoginRequest(email, pass)
        val requestBody = json.encodeToString(DabLoginRequest.serializer(), loginRequest)
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://dab.yeet.su/api/auth/login")
            .post(requestBody)
            .build()

        val response: DabUserResponse = execute(request)
        return converter.toUser(response.user ?: error("User data is null after login"))
    }

    fun getMe(): User {
        val request = Request.Builder()
            .url("https://dab.yeet.su/api/auth/me")
            .get()
            .build()
        val response: DabUserResponse = execute(request)
        return converter.toUser(response.user ?: error("User data is null"))
    }

    fun getLibraryPlaylists(page: Int, pageSize: Int): PagedData<Playlist> {
        return PagedData.Continuous { continuation ->
            val pageNum = continuation?.toIntOrNull() ?: page
            val url =
                "https://dab.yeet.su/api/libraries?limit=$pageSize&offset=${(pageNum - 1) * pageSize}"
            val request = Request.Builder().url(url).build()
            val response: DabPlaylistResponse = execute(request)
            val playlists = response.libraries.map(converter::toPlaylist)
            Page(playlists, if (playlists.isNotEmpty()) (pageNum + 1).toString() else null)
        }
    }

    fun getPlaylistTracks(playlist: Playlist, page: Int, pageSize: Int): PagedData<Track> {
        return PagedData.Continuous { continuation ->
            val pageNum = continuation?.toIntOrNull() ?: page
            val url =
                "https://dab.yeet.su/api/libraries/${playlist.id}/tracks?limit=$pageSize&offset=${(pageNum - 1) * pageSize}"
            val request = Request.Builder().url(url).build()
            val response: DabTrackResponse = execute(request)
            val tracks = response.tracks.map(converter::toTrack)
            Page(tracks, if (tracks.isNotEmpty()) (pageNum + 1).toString() else null)
        }
    }
}

