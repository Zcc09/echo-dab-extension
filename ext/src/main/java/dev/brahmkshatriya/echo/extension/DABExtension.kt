package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.helpers.ClientException
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
import dev.brahmkshatriya.echo.extension.RateLimitInterceptor

class DABExtension : ExtensionClient, LoginClient.CustomInput, LibraryFeedClient, PlaylistClient,
    TrackClient, SearchFeedClient {

    private lateinit var settings: Settings
    private val client by lazy { OkHttpClient.Builder().addInterceptor(RateLimitInterceptor(500)).build() }
    private val converter by lazy { Converter() }
    private val api by lazy { DABApi(client, converter, settings) }
    private var currentUser: User? = null

    // From ExtensionClient
    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    override suspend fun getSettingItems(): List<Setting> {
        return emptyList()
    }

    override suspend fun onInitialize() {
        val sessionCookie = settings.getString("session_cookie")
        if (sessionCookie != null && currentUser == null) {
            currentUser = runCatching { api.getMe() }.getOrNull()
            if (currentUser == null) {
                settings.putString("session_cookie", null)
            }
        }
    }

    override suspend fun onExtensionSelected() {
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
        val user = api.loginAndSaveCookie(email, password)
        currentUser = user
        return listOf(user)
    }

    // From LoginClient
    override fun setLoginUser(user: User?) {
        if (user == null) {
            settings.putString("session_cookie", null)
            currentUser = null
        }
    }

    override suspend fun getCurrentUser(): User? {
        if (currentUser == null) {
            onInitialize()
        }
        return currentUser
    }

    // From LibraryFeedClient
    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        if (getCurrentUser() == null) throw ClientException.LoginRequired()

        val playlists: PagedData<Playlist> = api.getLibraryPlaylists(1, 50)

        val playlistsAsShelves = playlists.map { result ->
            result.getOrThrow().map { playlist -> Shelf.Item(playlist) }
        } as PagedData<Shelf>

        return playlistsAsShelves.toFeed()
    }

    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        return playlist
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
        // Extract the track ID from the stream URL
        val trackId = streamable.extras["url"]?.substringAfter("trackId=") ?: error("Track ID not found in URL")

        val streamUrl = api.getStreamUrl(trackId)

        return streamUrl.toServerMedia()
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? {
        return null
    }

    // From SearchFeedClient
    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        if (query.isBlank()) {
            return emptyList<Shelf>().toFeed()
        }
        val allTracks = api.search(query, 1, 50).loadAll()
        val shelf = Shelf.Lists.Tracks(
            id = "search_results",
            title = "Search Results for \"$query\"",
            list = allTracks,
            more = null
        )
        return listOf(shelf).toFeed()
    }
}