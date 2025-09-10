package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toServerMedia
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings
import okhttp3.OkHttpClient

/**
 * This is the main implementation of your extension.
 * It implements all the necessary `Client` interfaces to provide functionality to the Echo app.
 */
class DABExtension : ExtensionClient, LoginClient, LibraryFeedClient, PlaylistClient, TrackClient {

    private lateinit var settings: Settings
    private val client by lazy { OkHttpClient() }
    private val converter by lazy { Converter() }
    private val api by lazy { DABApi(client, converter, settings) }

    // From ExtensionClient (via SettingsProvider)
    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    // From ExtensionClient (via SettingsProvider)
    override suspend fun getSettingItems(): List<Setting> {
        return emptyList()
    }

    // From LoginClient
    override suspend fun getLoginStatus(): Boolean {
        return settings.getString("token") != null
    }

    // From LoginClient
    override suspend fun login(credentials: Map<String, String>): User {
        val email = credentials["email"] ?: error("Email is required")
        val password = credentials["password"] ?: error("Password is required")
        val userToken = api.login(email, password)
        settings.putString("token", userToken)
        return api.getMe()
    }

    // From LoginClient
    override suspend fun logout() {
        settings.putString("token", null)
    }

    // From LoginClient
    override suspend fun getCurrentUser(): User? {
        return runCatching { api.getMe() }.getOrNull()
    }

    // From LoginClient
    override fun getLoginSettings(): List<Setting> {
        return listOf(
            SettingTextInput(
                key = "email",
                title = "Email",
                summary = "Your DAB email",
                defaultValue = ""
            ),
            SettingTextInput(
                key = "password",
                title = "Password",
                summary = "Your DAB password",
                defaultValue = ""
            )
        )
    }

    // From LoginClient
    override fun setLoginUser(user: User?) {
        // This is a required function, but may not be needed for your specific login flow.
    }

    // From LibraryFeedClient
    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        val playlists = api.getLibraryPlaylists(1, 50).loadAll()
        val shelf = Shelf.Lists.Items(
            id = "library_playlists",
            title = "My Playlists",
            list = playlists,
            more = null
        )
        return listOf(shelf).toFeed()
    }

    // From PlaylistClient
    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        // We will just return the playlist as is, as the API doesn't provide a single playlist endpoint.
        // To update track count, we would need to load all tracks, which can be slow.
        return playlist
    }

    // From PlaylistClient
    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        val tracks: PagedData<Track> = api.getPlaylistTracks(playlist, 1, 200)
        return tracks.toFeed()
    }

    // From PlaylistClient
    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? {
        return null
    }

    // From TrackClient
    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        return track
    }

    // From TrackClient
    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
        val url = streamable.extras["url"] ?: error("Streamable URL not found")
        return url.toServerMedia()
    }

    // From TrackClient
    override suspend fun loadFeed(track: Track): Feed<Shelf>? {
        return null
    }
}

