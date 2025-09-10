package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.extension.models.*
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

class DABApi(private val client: OkHttpClient, private val converter: Converter) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private inline fun <reified T> execute(request: Request): T {
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: error("Empty response body")
        if (!response.isSuccessful) {
            error("API Error: ${response.code} - $body")
        }
        return json.decodeFromString(body)
    }

    fun login(email: String, pass: String): String {
        val formBody = FormBody.Builder()
            .add("email", email)
            .add("password", pass)
            .build()
        val request = Request.Builder()
            .url("https://dab.yeet.su/api/auth/login")
            .post(formBody)
            .build()
        val response: DabAuthResponse = execute(request)
        return response.token
    }

    fun getMe(token: String): User {
        val request = Request.Builder()
            .url("https://dab.yeet.su/api/me")
            .header("Authorization", "Bearer $token")
            .build()
        val response: DabUserResponse = execute(request)
        return converter.toUser(response.data)
    }

    fun getLibraryPlaylists(token: String, page: Int, pageSize: Int): PagedData<Playlist> {
        return PagedData.Continuous { continuation ->
            val pageNum = continuation?.toIntOrNull() ?: page
            val url = "https://dab.yeet.su/api/me/library/playlists?limit=$pageSize&offset=${(pageNum - 1) * pageSize}"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()
            val response: DabPlaylistResponse = execute(request)
            val playlists = response.data.map(converter::toPlaylist)
            Page(playlists, if (response.meta.hasMore) (pageNum + 1).toString() else null)
        }
    }

    fun getPlaylistTracks(playlist: Playlist, page: Int, pageSize: Int): PagedData<Track> {
        return PagedData.Continuous { continuation ->
            val pageNum = continuation?.toIntOrNull() ?: page
            val url = "https://dab.yeet.su/api${playlist.id}/tracks?limit=$pageSize&offset=${(pageNum - 1) * pageSize}"
            val request = Request.Builder().url(url).build()
            val response: DabTrackResponse = execute(request)
            val tracks = response.data.map(converter::toTrack)
            Page(tracks, if (response.meta.hasMore) (pageNum + 1).toString() else null)
        }
    }
}