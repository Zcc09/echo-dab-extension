package dev.brahmkshatriya.echo.extension

import android.util.Log
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

    // No CookieJar. We will handle cookies manually.
    private val client = httpClient

    // Special function for login to find and save the session cookie.
    fun loginAndSaveCookie(email: String, pass: String): User {
        val loginRequest = DabLoginRequest(email, pass)
        val requestBody = json.encodeToString(DabLoginRequest.serializer(), loginRequest)
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://dab.yeet.su/api/auth/login")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()

        // Manually find the 'Set-Cookie' header from the login response.
        val cookieHeader = response.headers["Set-Cookie"]
        if (cookieHeader != null) {
            // Extract the 'session=...' part of the header.
            val sessionCookie = cookieHeader.split(';').firstOrNull { it.trim().startsWith("session=") }
            if (sessionCookie != null) {
                // Save the cookie to settings for future requests.
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

    // A new executor for all authenticated requests.
    private inline fun <reified T> executeAuthenticated(url: String): T {
        // Retrieve the saved cookie from settings.
        val sessionCookie = settings.getString("session_cookie")
            ?: error("Not logged in: session cookie not found.")

        // Build the request and manually add the Cookie header.
        val request = Request.Builder()
            .url(url)
            .header("Cookie", sessionCookie)
            .build()

        val response = client.newCall(request).execute()
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
            val url =
                "https://dab.yeet.su/api/libraries/${playlist.id}/tracks?limit=$pageSize&offset=${(pageNum - 1) * pageSize}"
            val response: DabTrackResponse = executeAuthenticated(url)
            val tracks = response.tracks.map(converter::toTrack)
            Page(tracks, if (tracks.isNotEmpty()) (pageNum + 1).toString() else null)
        }
    }

    fun search(query: String, page: Int, pageSize: Int): PagedData<Track> {
        return PagedData.Continuous { continuation ->
            val pageNum = continuation?.toIntOrNull() ?: page
            val url =
                "https://dab.yeet.su/api/search?q=$query&limit=$pageSize&offset=${(pageNum - 1) * pageSize}"
            // Search does not require authentication
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
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