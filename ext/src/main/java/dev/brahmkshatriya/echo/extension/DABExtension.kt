package dev.brahmkshatriya.echo.extension

import android.content.Context
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.providers.SettingsProvider
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings
import okhttp3.OkHttpClient

class DABExtension(context: Context) : Extension(context) {

    override val name = "DAB Music"
    override val id = "dab"

    override val client: ExtensionClient = object : LoginClient, LibraryFeedClient, PlaylistClient, TrackClient {

        private lateinit var settings: Settings
        private val client by lazy { OkHttpClient() }
        private val converter by lazy { Converter() }
        private val api by lazy { DABApi(client, converter) }

        private val token: String?
            get() = settings.getString("token")

        override fun setup(settings: SettingsProvider) {
            settings.setSettings(object : Settings {
                override fun getString(key: String, defaultValue: String?): String? {
                    // Implement your logic to get from shared preferences or other storage
                    return defaultValue
                }
                override fun set(key: String, value: String?) {
                    // Implement your logic to save to shared preferences or other storage
                }
            }.also { this.settings = it })
        }

        override suspend fun getLoginStatus() = token != null

        override suspend fun login(credentials: Map<String, String>): User {
            val email = credentials["email"] ?: error("Email is required")
            val password = credentials["password"] ?: error("Password is required")
            val userToken = api.login(email, password)
            settings.set("token", userToken)
            return api.getMe(userToken)
        }

        override suspend fun logout() {
            settings.set("token", null)
        }

        override suspend fun getCurrentUser(): User? {
            val currentToken = token ?: return null
            return runCatching { api.getMe(currentToken) }.getOrNull()
        }

        override fun getLoginSettings(): List<Setting> {
            return listOf(
                SettingTextInput("email", "Email", "Your DAB email", ""),
                SettingTextInput("password", "Password", "Your DAB password", "", true)
            )
        }

        override suspend fun loadLibraryFeed(): Feed<Shelf> {
            val currentToken = token ?: error("Not logged in")
            val playlists = api.getLibraryPlaylists(currentToken, 1, 50).loadAll()
            val shelf = Shelf.Lists.Tracks("My Playlists", playlists, more = null)
            return Feed(listOf(shelf))
        }

        override suspend fun loadPlaylist(playlist: Playlist): Playlist {
            val tracks = api.getPlaylistTracks(playlist, 1, 200).loadAll()
            return playlist.copy(tracks = tracks)
        }

        override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
            val tracks = api.getPlaylistTracks(playlist, 1, 200)
            return Feed.Paged(tracks)
        }

        override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
            val url = streamable.extras["url"] ?: error("Streamable URL not found")
            return Streamable.Media.fromUrl(url)
        }

        override suspend fun loadTrack(track: EchoMediaItem.Track, isDownload: Boolean): EchoMediaItem.Track {
            return track
        }

        override fun setLoginUser(user: User?) {
            // Not needed for this extension
        }

        override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? {
            return null
        }

        override suspend fun loadFeed(track: Track): Feed<Shelf>? {
            return null
        }
    }
}