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
import dev.brahmkshatriya.echo.common.settings.Settings
import okhttp3.OkHttpClient

class DABExtension : ExtensionClient, LoginClient.CustomInput, LibraryFeedClient, PlaylistClient,
    TrackClient {

    private lateinit var settings: Settings
    private val client by lazy { OkHttpClient() }
    private val converter by lazy { Converter() }
    private val api by lazy { DABApi(client, converter, settings) }

    // From ExtensionClient
    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    override suspend fun getSettingItems(): List<Setting> {
        return emptyList()
    }

    override suspend fun onInitialize() {
        // Code to run once when the extension is first loaded.
    }

    override suspend fun onExtensionSelected() {
        // Code to run when the user selects this extension in the app.
    }

    // From LoginClient.CustomInput
    override val forms: List<LoginClient.Form>
        get() = listOf(
            LoginClient.Form(
                key = "login_form",
                label = "DAB Music Player Login",
                icon = LoginClient.InputField.Type.Misc,
                inputFields = listOf(
                    LoginClient.InputField(
                        type = LoginClient.InputField.Type.Email,
                        key = "email",
                        label = "Email",
                        isRequired = true
                    ),
                    LoginClient.InputField(
                        type = LoginClient.InputField.Type.Password,
                        key = "password",
                        label = "Password",
                        isRequired = true
                    )
                )
            )
        )

    override suspend fun onLogin(key: String, data: Map<String, String?>): List<User> {
        val email = data["email"] ?: error("Email is required")
        val password = data["password"] ?: error("Password is required")
        val user = api.login(email, password)
        return listOf(user)
    }

    // From LoginClient
    override fun setLoginUser(user: User?) {
        if (user == null) {
            settings.putString("token", null)
            // Here you would also clear any cookies if your API used them for sessions.
        }
    }

    override suspend fun getCurrentUser(): User? {
        return if (settings.getString("token") != null) {
            runCatching { api.getMe() }.getOrNull()
        } else {
            null
        }
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
        val tracks = api.getPlaylistTracks(playlist, 1, 200).loadAll()
        return playlist.copy(trackCount = tracks.size.toLong())
    }

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        val tracks: PagedData<Track> = api.getPlaylistTracks(playlist, 1, 200)
        return tracks.toFeed()
    }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? {
        return null
    }

    // From TrackClient
    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        return track
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean
    ): Streamable.Media {
        val url = streamable.extras["url"] ?: error("Streamable URL not found")
        return url.toServerMedia()
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? {
        return null
    }
}

