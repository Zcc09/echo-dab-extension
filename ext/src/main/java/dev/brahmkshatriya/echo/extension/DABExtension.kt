package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
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
import kotlinx.serialization.json.jsonObject

class DABExtension :
    ExtensionClient,
    HomeFeedClient,
    SearchFeedClient,
    LibraryFeedClient,
    AlbumClient,
    ArtistClient,
    PlaylistClient,
    TrackClient,
    LyricsClient {

    private lateinit var settings: Settings
    private val api by lazy { DABApi() }

    override fun setSettings(settings: Settings) {
        this.settings = settings
        api.setSettings(settings)
    }

    override suspend fun getSettingItems(): List<Setting> {
        return emptyList()
    }

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val apiKey = "YOUR_API_KEY_HERE"

        if (apiKey == "YOUR_API_KEY_HERE" || apiKey.isBlank()) {
            // FIX: Use Feed.Page and explicitly state the type for the empty list.
            return Feed.Page(emptyList<Shelf>())
        }
        val response = LastFmApi(apiKey).getHomeFeed()
        val shelves = response["sections"]?.let { sections ->
            (sections as JsonArray).mapNotNull { section ->
                val sectionJson = section.jsonObject
                val title = sectionJson["title"]?.toString() ?: "Unknown"
                val items = (sectionJson["items"] as JsonArray).mapNotNull {
                    it.jsonObject.toTrack()
                }
                Shelf.Lists.Tracks(title, title, items)
            }
        } ?: emptyList()
        return Feed.Page(shelves) // FIX: Use Feed.Page
    }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> = coroutineScope {
        val tracksDeferred = async { api.search(query, "track") }
        val albumsDeferred = async { api.search(query, "album") }
        val artistsDeferred = async { api.search(query, "artist") }

        val shelves = mutableListOf<Shelf>()

        val tracks = tracksDeferred.await()["results"]
            ?.let { (it as JsonArray).mapNotNull { item -> item.jsonObject.toTrack() } }
        if (!tracks.isNullOrEmpty()) shelves.add(Shelf.Lists.Tracks("tracks", "Tracks", tracks))

        val albums = albumsDeferred.await()["results"]
            ?.let { (it as JsonArray).mapNotNull { item -> item.jsonObject.toAlbum() } }
        if (!albums.isNullOrEmpty()) shelves.add(Shelf.Lists.Items("albums", "Albums", albums))

        val artists = artistsDeferred.await()["results"]
            ?.let { (it as JsonArray).mapNotNull { item -> item.jsonObject.toArtist() } }
        if (!artists.isNullOrEmpty()) shelves.add(Shelf.Lists.Items("artists", "Artists", artists))

        return@coroutineScope Feed.Page(shelves) // FIX: Use Feed.Page
    }


    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        val libraries = api.getLibraries()
        val playlists = libraries["libraries"]
            ?.let { (it as JsonArray).mapNotNull { item -> item.jsonObject.toPlaylist() } }
            ?: emptyList()
        return Feed.Page(listOf(Shelf.Lists.Items("playlists", "Playlists", playlists))) // FIX: Use Feed.Page
    }

    override suspend fun loadAlbum(album: Album): Album {
        val albumDetails = api.getAlbum(album.id)
        return albumDetails["album"]?.jsonObject?.toAlbum() ?: album
    }

    override suspend fun loadTracks(album: Album): Feed<Track> {
        val albumDetails = api.getAlbum(album.id)
        val tracks = albumDetails["album"]?.jsonObject?.get("tracks")
            ?.let { (it as JsonArray).mapNotNull { item -> item.jsonObject.toTrack() } }
            ?: emptyList()
        return Feed.Page(tracks) // FIX: Use Feed.Page
    }

    override suspend fun loadArtist(artist: Artist): Artist {
        val discography = api.getDiscography(artist.id)
        return discography["artist"]?.jsonObject?.toArtist() ?: artist
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val discography = api.getDiscography(artist.id)
        val albums = discography["albums"]
            ?.let { (it as JsonArray).mapNotNull { item -> item.jsonObject.toAlbum() } }
            ?: emptyList()
        return Feed.Page(listOf(Shelf.Lists.Items("albums", "Albums", albums))) // FIX: Use Feed.Page
    }

    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        val playlistDetails = api.getLibrary(playlist.id)
        return playlistDetails["library"]?.jsonObject?.toPlaylist() ?: playlist
    }

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        val playlistDetails = api.getLibrary(playlist.id, 1)
        val tracks = playlistDetails["library"]?.jsonObject?.get("tracks")
            ?.let { (it as JsonArray).mapNotNull { item -> item.jsonObject.toTrack() } }
            ?: emptyList()
        return Feed.Page(tracks) // FIX: Use Feed.Page
    }

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        return track
    }

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
        val streamUrl = api.getStreamUrl(streamable.id)
        return Streamable.Media(url = streamUrl["streamUrl"].toString()) // FIX: Use direct constructor
    }

    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> {
        val artist = track.artists.firstOrNull()?.name ?: return Feed.Page(emptyList<Lyrics>())
        val lyricsJson = api.getLyrics(artist, track.title)
        val lyricsText = lyricsJson["lyrics"]?.toString()
        return if (lyricsText != null) {
            val isSynced = lyricsJson["unsynced"]?.toString()?.toBoolean() == false
            Feed.Page(listOf(Lyrics(lyricsText, if (isSynced) Lyrics.Type.SYNCED else Lyrics.Type.UNSYNCED)))
        } else {
            Feed.Page(emptyList<Lyrics>())
        }
    }

    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics {
        return lyrics
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? = null
    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = null
    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null
}