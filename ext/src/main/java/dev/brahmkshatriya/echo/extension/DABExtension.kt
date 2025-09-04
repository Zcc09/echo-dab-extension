package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingCategory
import dev.brahmkshatriya.echo.common.settings.SettingList
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.extension.clients.DABAlbumClient
import dev.brahmkshatriya.echo.extension.clients.DABArtistClient
import dev.brahmkshatriya.echo.extension.clients.DABHomeFeedClient
import dev.brahmkshatriya.echo.extension.clients.DABLibraryClient
import dev.brahmkshatriya.echo.extension.clients.DABLyricsClient
import dev.brahmkshatriya.echo.extension.clients.DABPlaylistClient
import dev.brahmkshatriya.echo.extension.clients.DABSearchClient
import dev.brahmkshatriya.echo.extension.clients.DABTrackClient
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

class DABExtension :
    HomeFeedClient,
    LikeClient,
    SearchFeedClient,
    QuickSearchClient,
    AlbumClient,
    ArtistClient,
    PlaylistClient,
    LyricsClient,
    LibraryFeedClient,
    LoginClient.CustomInput,
    ShareClient {

    private val session by lazy { DABSession.getInstance() }
    private val api by lazy { DABApi(session) }
    private val parser by lazy { DABParser(session) }

    private val homeFeedClient by lazy { DABHomeFeedClient(api, parser) }
    private val trackClient by lazy { DABTrackClient(api, session) }
    private val albumClient by lazy { DABAlbumClient(api, parser) }
    private val artistClient by lazy { DABArtistClient(api, parser) }
    private val playlistClient by lazy { DABPlaylistClient(api, parser) }
    private val searchClient by lazy { DABSearchClient(api, parser) }
    private val lyricsClient by lazy { DABLyricsClient(api) }
    private val libraryClient by lazy { DABLibraryClient(api, parser, session) }

    //<============= Settings =============>
    override suspend fun getSettingItems(): List<Setting> = listOf(
        SettingCategory(
            title = "Quality",
            key = "quality",
            items = mutableListOf(
                SettingList(
                    title = "Audio Quality",
                    key = "audio_quality",
                    summary = "Choose your preferred audio quality. The player will try to match this or pick the next best option.",
                    entryTitles = mutableListOf("Best Available", "High (e.g. 320kbps)", "Standard (e.g. 128kbps)"),
                    entryValues = mutableListOf("best", "high", "standard"),
                    defaultEntryIndex = 0
                )
            )
        )
    )

    override fun setSettings(settings: Settings) {
        session.settings = settings
    }

    //<============= Login =============>
    override val forms: List<LoginClient.Form> = listOf(
        LoginClient.Form(
            key = "token",
            label = "Auth Token",
            icon = LoginClient.InputField.Type.Misc,
            inputFields = listOf(
                LoginClient.InputField(
                    type = LoginClient.InputField.Type.Password,
                    key = "token",
                    label = "Token",
                    isRequired = true,
                )
            )
        )
    )

    // FIXED: Corrected implementation for onLogin
    override suspend fun onLogin(key: String, data: Map<String, String?>): List<User> {
        val token = data["token"]
        if (token.isNullOrBlank()) {
            throw ClientException.InvalidLogin("Auth token cannot be empty.")
        }

        session.initialize(token) // Initialize session with the provided token
        return try {
            // Attempt to fetch the current user to validate the token
            listOf(getCurrentUser())
        } catch (e: Exception) {
            session.clear() // Clear session if token is invalid
            throw ClientException.LoginFailed("Login failed: ${e.message}") // Use correct constructor
        }
    }

    override fun setLoginUser(user: User?) {
        val token = user?.extras?.get("token")
        if (token != null) {
            session.initialize(token)
        } else {
            session.clear()
        }
    }

    // FIXED: Corrected implementation for getCurrentUser
    override suspend fun getCurrentUser(): User {
        return try {
            val userJson = api.getMe() // Call the API to get user details
            val userId = userJson["id"]?.jsonPrimitive?.content ?: throw IllegalStateException("User ID not found in /me response.")
            val userName = userJson["name"]?.jsonPrimitive?.content ?: "DAB User" // Default name if not provided
            val userAvatar = userJson["avatarUrl"]?.jsonPrimitive?.content

            User(
                id = userId,
                name = userName,
                extras = mapOf("token" to session.credentials.token) as Map<String, String> // Store token for session restoration
            )
        } catch (e: Exception) {
            session.clear() // Clear session if API call fails (e.g., invalid token)
            throw ClientException.LoginRequired("Failed to retrieve current user: ${e.message}") // Use correct constructor
        }
    }

    //<============= Home Feed =============>
    override suspend fun loadHomeFeed(): Feed<Shelf> = homeFeedClient.loadHomeFeed()

    //<============= Library (Favorites) =============>
    override suspend fun loadLibraryFeed(): Feed<Shelf> = libraryClient.loadLibraryFeed()

    override suspend fun isItemLiked(item: EchoMediaItem): Boolean {
        // You would typically call an API to check if an item is favorited.
        // The DAB API example only shows add/remove, not check if favorite for track specifically.
        // Assuming your DABApi.getTrack() might return an "isFavorite" field as in previous context
        return when (item) {
            is Track -> api.getTrack(item.id)["isFavorite"]?.jsonPrimitive?.boolean ?: false
            else -> false // Extend this logic for other types if your API supports it
        }
    }

    override suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean) {
        // The DAB API doc shows /favorites for tracks. Adjust if albums/artists have separate endpoints.
        val itemType = when (item) {
            is Album -> "album"   // Added Album type support
            is Artist -> "artist" // Added Artist type support
            is Track -> "track"
            // Add other types if your API supports liking them, e.g., Playlist
            else -> throw IllegalArgumentException("Liking for item type ${item::class.simpleName} not supported by DAB API.")
        }

        if (shouldLike) {
            // FIXED: Passed itemType to addFavorite
            api.addFavorite(item.id, itemType)
        } else {
            // FIXED: Passed itemType to removeFavorite
            api.removeFavorite(item.id, itemType)
        }
    }


    //<============= Search =============>
    override suspend fun quickSearch(query: String): List<QuickSearchItem> = searchClient.quickSearch(query)
    override suspend fun loadSearchFeed(query: String): Feed<Shelf> = searchClient.loadSearchFeed(query)
    override suspend fun deleteQuickSearch(item: QuickSearchItem) {
        // The DAB API does not support deleting search history items.
        // This function is implemented to satisfy the interface, but it does nothing.
    }

    //<============= Playback and Content Loading =============>
    suspend fun loadTrack(track: Track, isDownload: Boolean): Track = trackClient.loadTrack(track)
    suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media = trackClient.loadStreamable(streamable)
    override suspend fun loadAlbum(album: Album): Album = albumClient.loadAlbum(album)

    override suspend fun loadTracks(album: Album): Feed<Track>? = albumClient.loadTracks(album)
    override suspend fun loadFeed(album: Album): Feed<Shelf>? {
        TODO("Not yet implemented")
    }

    override suspend fun loadArtist(artist: Artist): Artist = artistClient.loadArtist(artist)
    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val specificFeed = artistClient.getShelves(artist)
        return Feed(
            tabs = specificFeed.tabs,
            getPagedData = { tab ->
                val specificData = specificFeed.getPagedData(tab)
                val specificPagedData = specificData.pagedData
                // Map each Shelf.Lists.Items to a List<Shelf>
                val genericPagedData = specificPagedData.map { listOf(it as Shelf) }
                Feed.Data(
                    pagedData = genericPagedData,
                    buttons = specificData.buttons,
                    background = specificData.background
                )
            }
        )
    }

    override suspend fun loadPlaylist(playlist: Playlist): Playlist = playlistClient.loadPlaylist(playlist)
    override suspend fun loadTracks(playlist: Playlist): Feed<Track> = playlistClient.loadTracks(playlist)

    //<============= "Related" Feeds =============>
    // A sensible default for related content is the primary artist's page.
    // Use the helper extension function for related feeds as well

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = null // Playlists don't have a clear "related" feed.

    //<============= Lyrics =============>
    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> = lyricsClient.searchTrackLyrics(track)
    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics = lyrics

    //<============= Sharing =============>
    override suspend fun onShare(item: EchoMediaItem): String {
        val type = when (item) {
            is Album -> "album"
            is Artist -> "artist"
            is Track -> "track"
            is Playlist -> "playlist"
            else -> "item"
        }
        return "https://dab.yeet.su/$type/${item.id}"
    }
}
