package dev.brahmkshatriya.echo.extension

import android.util.Log
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.models.*
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
                Log.d("DAB_DEBUG", "Session cookie saved successfully.")
            }
        }

        val body = response.body?.string() ?: error("Empty response body")
        Log.d("DAB_API", "Response for ${request.url}: $body")
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

        Log.d("DAB_API", "Response for ${request.url}: $body")

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
            ?: error("Not logged in: session cookie not found.")

        val url = "https://dab.yeet.su/api/stream?trackId=$trackId"
        val request = Request.Builder()
            .url(url)
            .header("Cookie", sessionCookie)
            .build()

        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Empty response"
            error("API Error: ${response.code} - $errorBody")
        }

        // Check if response is a redirect (302) with Location header
        val location = response.header("Location")
        if (location != null) {
            Log.d("DAB_API", "Stream redirect for track $trackId: $location")
            return location
        }

        // Read the body only after checking for redirects
        val body = response.body?.string() ?: error("Empty response body")
        Log.d("DAB_API", "Stream response for track $trackId: $body")

        return try {
            val streamResponse: DabStreamResponse = json.decodeFromString(body)
            streamResponse.streamUrl ?: streamResponse.url ?: streamResponse.stream ?: streamResponse.link
            ?: error("No stream URL found in JSON response")
        } catch (e: kotlinx.serialization.SerializationException) {
            // If JSON parsing fails, check if response is a direct URL
            if (body.trim().startsWith("http")) {
                body.trim()
            } else {
                Log.e("DAB_API", "Failed to parse stream response: $body", e)
                error("API did not return a stream URL.")
            }
        }
    }

    fun getLibraryPlaylists(page: Int, pageSize: Int): PagedData<Playlist> {
        return PagedData.Continuous { continuation ->
            val pageNum = continuation?.toIntOrNull() ?: page
            val url =
                "https://dab.yeet.su/api/libraries?limit=$pageSize&offset=${(pageNum - 1) * pageSize}"
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
            val url =
                "https://dab.yeet.su/api/search?q=$query&limit=$pageSize&offset=${(pageNum - 1) * pageSize}"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: error("Empty response body")
            Log.d("DAB_API", "Response for ${request.url}: $body")
            if (!response.isSuccessful) {
                error("API Error: ${response.code} ${response.message} - $body")
            }
            val trackResponse: DabTrackResponse = json.decodeFromString(body)
            val tracks = trackResponse.tracks.map(converter::toTrack)
            Page(tracks, if (tracks.isNotEmpty()) (pageNum + 1).toString() else null)
        }
    }
}
