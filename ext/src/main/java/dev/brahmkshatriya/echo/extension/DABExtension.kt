package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.DABParser.toAlbum
import dev.brahmkshatriya.echo.extension.DABParser.toArtist
import dev.brahmkshatriya.echo.extension.DABParser.toPlaylist
import dev.brahmkshatriya.echo.extension.DABParser.toTrack
import dev.brahmkshatriya.echo.extension.DABParser.toUser
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DABExtension :
    ExtensionClient,
    LoginClient.CustomInput, // Corrected Interface
    HomeFeedClient,
    SearchFeedClient,
    LibraryFeedClient,
    AlbumClient,
    ArtistClient,
    PlaylistClient,
    TrackClient,
    LyricsClient,
    LikeClient,
    ShareClient {

    private lateinit var settings: Settings
    private val api by lazy { DABApi() }
    private var currentUser: User? = null

    override fun setSettings(settings: Settings) {
        this.settings = settings
        api.setSettings(settings)
    }

    override suspend fun getSettingItems(): List<Setting> {
        return emptyList()
    }

    // Correct implementation for LoginClient.CustomInput
    override val forms: List<LoginClient.Form>
        get() = listOf(
            LoginClient.Form(
                key = "dab_login",
                label = "DAB Login",
                icon = LoginClient.InputField.Type.Email,
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

    // Correct implementation for LoginClient.CustomInput
    override suspend fun onLogin(key: String, data: Map<String, String?>): List<User> {
        if (key != "dab_login") return emptyList()

        val email = data["email"] ?: throw Exception("Email cannot be empty.")
        val password = data["password"] ?: throw Exception("Password cannot be empty.")

        return try {
            val response = api.login(email, password)
            val user = response["user"]?.jsonObject?.toUser()
                ?: throw Exception("Login failed, please check your credentials.")
            listOf(user)
        } catch (e: DABApi.DABApiException) {
            throw Exception(e.message)
        }
    }

    // Correct implementation for LoginClient
    override fun setLoginUser(user: User?) {
        currentUser = user
    }

    // Correct implementation for LoginClient
    override suspend fun getCurrentUser(): User? {
        if (currentUser == null) {
            runCatching {
                val response = api.me()
                response["user"]?.jsonObject?.let {
                    currentUser = it.toUser()
                }
            }
        }
        return currentUser
    }

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val apiKey = "60062db323588d3e61849931b747055c"
        if (apiKey.isBlank()) {
            return PagedData.Single<Shelf> { emptyList() }.toFeed()
        }
        val response = LastFmApi(apiKey).getHomeFeed()
        val shelves: List<Shelf> = response["sections"]?.let { sections ->
            (sections as JsonArray).mapNotNull { section ->
                val sectionJson = section.jsonObject
                val title = sectionJson["title"]?.jsonPrimitive?.content ?: "Unknown"
                val items = (sectionJson["items"] as JsonArray).mapNotNull {
                    it.jsonObject.toTrack()
                }
                if (items.isNotEmpty()) {
                    Shelf.Lists.Tracks(title, title, items)
                } else {
                    null
                }
            }
        } ?: emptyList()
        return PagedData.Single { shelves }.toFeed()
    }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> = coroutineScope {
        if (query.isBlank()) return@coroutineScope PagedData.Single<Shelf> { emptyList() }.toFeed()

        val tracksDeferred = async { api.search(query, "track") }
        val albumsDeferred = async { api.search(query, "album") }
        val artistsDeferred = async { api.search(query, "artist") }

        val shelves = mutableListOf<Shelf>()

        runCatching {
            tracksDeferred.await()["results"]
                ?.let { (it as JsonArray).mapNotNull { item -> item.jsonObject.toTrack() } }
                ?.takeIf { it.isNotEmpty() }
                ?.let { shelves.add(Shelf.Lists.Tracks("tracks", "Tracks", it)) }
        }
        runCatching {
            albumsDeferred.await()["results"]
                ?.let { (it as JsonArray).mapNotNull { item -> item.jsonObject.toAlbum() } }
                ?.takeIf { it.isNotEmpty() }
                ?.let { shelves.add(Shelf.Lists.Items("albums", "Albums", it)) }
        }
        runCatching {
            artistsDeferred.await()["results"]
                ?.let { (it as JsonArray).mapNotNull { item -> item.jsonObject.toArtist() } }
                ?.takeIf { it.isNotEmpty() }
                ?.let { shelves.add(Shelf.Lists.Items("artists", "Artists", it)) }
        }

        val tabs = listOf(
            Tab("All", "All"),
            Tab("Tracks", "Tracks"),
            Tab("Albums", "Albums"),
            Tab("Artists", "Artists")
        )

        return@coroutineScope Feed(tabs) { tab ->
            val filteredShelves: List<Shelf> = when (tab?.id) {
                "Tracks" -> shelves.filterIsInstance<Shelf.Lists.Tracks>()
                "Albums" -> shelves.filter { it.id == "albums" }
                "Artists" -> shelves.filter { it.id == "artists" }
                else -> shelves
            }
            PagedData.Single { filteredShelves }.toFeedData()
        }
    }


    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        val shelves = mutableListOf<Shelf>()
        runCatching {
            val libraries = api.getLibraries()
            libraries["libraries"]
                ?.let { (it as JsonArray).mapNotNull { item -> item.jsonObject.toPlaylist() } }
                ?.takeIf { it.isNotEmpty() }
                ?.let { shelves.add(Shelf.Lists.Items("playlists", "Playlists", it)) }
        }
        runCatching {
            api.getFavorites()["favorites"]
                ?.let { (it as JsonArray).mapNotNull { item -> item.jsonObject.toTrack() } }
                ?.takeIf { it.isNotEmpty() }
                ?.let { shelves.add(Shelf.Lists.Tracks("favorites", "Favorites", it)) }
        }
        return PagedData.Single { shelves }.toFeed()
    }

    override suspend fun loadAlbum(album: Album): Album {
        return api.getAlbum(album.id)["album"]?.jsonObject?.toAlbum() ?: album
    }

    override suspend fun loadTracks(album: Album): Feed<Track> {
        val tracks = api.getAlbum(album.id)["album"]?.jsonObject?.get("tracks")
            ?.let { (it as JsonArray).mapNotNull { item -> item.jsonObject.toTrack() } }
            ?: emptyList()
        return PagedData.Single { tracks }.toFeed()
    }

    override suspend fun loadArtist(artist: Artist): Artist {
        return api.getDiscography(artist.id)["artist"]?.jsonObject?.toArtist() ?: artist
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val albums = api.getDiscography(artist.id)["albums"]
            ?.let { (it as JsonArray).mapNotNull { item -> item.jsonObject.toAlbum() } }
            ?: emptyList()
        val shelfList: List<Shelf> = listOf(Shelf.Lists.Items("albums", "Albums", albums))
        return PagedData.Single { shelfList }.toFeed()
    }

    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        return api.getLibrary(playlist.id)["library"]?.jsonObject?.toPlaylist() ?: playlist
    }

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        return PagedData.Continuous<Track> { continuation ->
            val currentPage = continuation?.toIntOrNull() ?: 1
            val playlistDetails = api.getLibrary(playlist.id, currentPage)
            val tracks = playlistDetails["library"]?.jsonObject?.get("tracks")
                ?.let { (it as JsonArray).mapNotNull { item -> item.jsonObject.toTrack() } }
                ?: emptyList()

            val pagination = playlistDetails["library"]?.jsonObject?.get("pagination")?.jsonObject
            val hasMore = pagination?.get("hasMore")?.jsonPrimitive?.boolean ?: false
            val nextContinuation = if (hasMore) (currentPage + 1).toString() else null

            Page(tracks, nextContinuation)
        }.toFeed()
    }

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        return track
    }

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
        val streamUrlResponse = api.getStreamUrl(streamable.id)
        val streamUrl = streamUrlResponse["streamUrl"]?.jsonPrimitive?.content
            ?: throw ClientException.NotSupported("Stream URL not found")
        return Streamable.Source.Http(NetworkRequest(streamUrl)).toMedia()
    }

    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> {
        val artistName = track.artists.firstOrNull()?.name ?: return PagedData.Single<Lyrics> { emptyList() }.toFeed()
        return try {
            val lyricsJson = api.getLyrics(artistName, track.title)
            val lyricsText = lyricsJson["lyrics"]?.jsonPrimitive?.content
            if (lyricsText != null) {
                // Corrected: Assumes the Lyrics constructor takes the content string directly.
                val lyricsObject = Lyrics(track.id, track.title, lyricsText)
                PagedData.Single { listOf(lyricsObject) }.toFeed()
            } else {
                PagedData.Single<Lyrics> { emptyList() }.toFeed()
            }
        } catch (e: DABApi.DABApiException) {
            if (e.code == 44) PagedData.Single<Lyrics> { emptyList() }.toFeed() else throw e
        }
    }


    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics {
        return lyrics
    }

    override suspend fun isItemLiked(item: EchoMediaItem): Boolean {
        if (item !is Track) return false
        return try {
            val favorites = api.getFavorites()["favorites"]
                ?.let { (it as JsonArray).mapNotNull { fav -> fav.jsonObject.toTrack() } }
                ?: emptyList()
            favorites.any { it.id == item.id }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean) {
        if (item !is Track) return
        try {
            if (shouldLike) {
                api.addToFavorites(item)
            } else {
                api.removeFromFavorites(item.id)
            }
        } catch (e: DABApi.DABApiException) {
            println("Failed to update like status: ${e.message}")
        }
    }

    override suspend fun onShare(item: EchoMediaItem): String {
        val type = when (item) {
            is Track -> "track"
            is Album -> "album"
            is Artist -> "artist"
            is Playlist -> "library"
            else -> "item"
        }
        return "https://dab.yeet.su/$type/${item.id}"
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? = null
    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = null
    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null
}

