package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
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

class DABExtension : ExtensionClient, LoginClient.CustomInput, LibraryFeedClient, PlaylistClient,
    TrackClient, SearchFeedClient, AlbumClient, ArtistClient, LyricsClient {

    private lateinit var settings: Settings
    // Use much shorter base delay to avoid slowing searches; RateLimitInterceptor still does exponential backoff on 429
    private val client by lazy { OkHttpClient.Builder().addInterceptor(RateLimitInterceptor(50)).build() }
    private val converter by lazy { Converter() }
    // Create the API and immediately assign it back to the converter so the converter can fetch images
    private val api: DABApi by lazy { DABApi(client, converter, settings).also { converter.api = it } }
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

    // From SearchFeedClient - Simple but effective search implementation
    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        if (query.isBlank()) {
            return emptyList<Shelf>().toFeed()
        }

        val shelves = mutableListOf<Shelf>()

        // Get all content types with error handling
        val tracks = runCatching { api.searchTracks(query, 8) }.getOrElse { emptyList<Track>() }
        val albums = runCatching { api.searchAlbums(query, 4) }.getOrElse { emptyList() }
        val artists = runCatching { api.searchArtists(query, 4) }.getOrElse { emptyList() }

        // Add tracks shelf if there are results
        if (tracks.isNotEmpty()) {
            shelves.add(
                Shelf.Lists.Tracks(
                    id = "search_tracks",
                    title = "🎵 Tracks",
                    list = tracks,
                    more = null
                )
            )
        }

        // Add album items directly as individual shelves
        if (albums.isNotEmpty()) {
            albums.forEach { album ->
                shelves.add(Shelf.Item(album))
            }
        }

        // Add artist items directly as individual shelves
        if (artists.isNotEmpty()) {
            artists.forEach { artist ->
                shelves.add(Shelf.Item(artist))
            }
        }

        return shelves.toFeed()
    }

    // --- AlbumClient ---
    override suspend fun loadAlbum(album: dev.brahmkshatriya.echo.common.models.Album): dev.brahmkshatriya.echo.common.models.Album {
        return api.getAlbum(album.id)?.let { converter.toAlbum(it) } ?: album
    }
    override suspend fun loadTracks(album: dev.brahmkshatriya.echo.common.models.Album): Feed<Track> {
        val tracks = api.getAlbum(album.id)?.tracks?.map { converter.toTrack(it) } ?: emptyList()
        return tracks.toFeed()
    }
    override suspend fun loadFeed(album: dev.brahmkshatriya.echo.common.models.Album): Feed<Shelf>? {
        val dabAlbum = api.getAlbum(album.id) ?: return null
        val shelves = mutableListOf<Shelf>()
        shelves.add(Shelf.Item(converter.toAlbum(dabAlbum)))

        val tracks = dabAlbum.tracks?.map { converter.toTrack(it) } ?: emptyList()
        if (tracks.isNotEmpty()) {
            shelves.add(Shelf.Lists.Tracks(
                id = "album_tracks_${album.id}",
                title = "Tracks",
                list = tracks
            ))
        }
        return shelves.toFeed()
    }

    // --- ArtistClient ---
    override suspend fun loadArtist(artist: dev.brahmkshatriya.echo.common.models.Artist): dev.brahmkshatriya.echo.common.models.Artist {
        val artistDetails = api.getArtistDetails(artist.id)
        return artistDetails?.let { converter.toArtist(it) } ?: artist
    }
    override suspend fun loadFeed(artist: dev.brahmkshatriya.echo.common.models.Artist): Feed<Shelf> {
        val shelves = mutableListOf<Shelf>()

        val discography = api.getArtistDiscography(artist.id)

        // Add a "Top Tracks" shelf from the first album
        val firstAlbum = discography.firstOrNull()
        if (firstAlbum != null) {
            val albumWithTracks = api.getAlbum(firstAlbum.id)
            val topTracks = albumWithTracks?.tracks?.map { converter.toTrack(it) }?.take(10) ?: emptyList()
            if (topTracks.isNotEmpty()) {
                shelves.add(Shelf.Lists.Tracks(
                    id = "artist_top_tracks_${artist.id}",
                    title = "Top Tracks",
                    list = topTracks
                ))
            }
        }

        // Add artist's albums
        val albums = discography.map { converter.toAlbum(it) }
        if (albums.isNotEmpty()) {
            shelves.add(Shelf.Lists.Items(
                id = "artist_albums_${artist.id}",
                title = "Albums",
                list = albums,
                type = Shelf.Lists.Type.Grid // Grid looks better for albums
            ))
        }

        return shelves.toFeed()
    }

    // --- LyricsClient ---
    override suspend fun loadLyrics(lyrics: dev.brahmkshatriya.echo.common.models.Lyrics): dev.brahmkshatriya.echo.common.models.Lyrics {
        val artist = lyrics.extras["artistName"] ?: return lyrics
        val lyricsText = try { api.getLyrics(artist, lyrics.title) } catch (_: Throwable) { null }

        if (lyricsText.isNullOrBlank()) return lyrics.copy(lyrics = dev.brahmkshatriya.echo.common.models.Lyrics.Simple(""))

        val lyricObj = try { converter.toLyricFromText(lyricsText) } catch (_: Throwable) { null }
        if (lyricObj != null) return lyrics.copy(lyrics = lyricObj)

        val fallback = dev.brahmkshatriya.echo.common.models.Lyrics.Simple(converter.cleanPlainText(lyricsText))
        return lyrics.copy(lyrics = fallback)
    }
    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<dev.brahmkshatriya.echo.common.models.Lyrics> {
        // searchTrackLyrics invoked
         val artistName = track.artists.firstOrNull()?.name ?: return emptyList<dev.brahmkshatriya.echo.common.models.Lyrics>().toFeed()
         val lyricsText = try { api.getLyrics(artistName, track.title) } catch (_: Throwable) { null }

        val lyricObj = try { lyricsText?.let { converter.toLyricFromText(it) } } catch (_: Throwable) { null }
        val finalLyric = lyricObj ?: dev.brahmkshatriya.echo.common.models.Lyrics.Simple(converter.cleanPlainText(lyricsText ?: ""))

        val lyrics = dev.brahmkshatriya.echo.common.models.Lyrics(
            id = track.id,
            title = track.title,
            subtitle = artistName,
            lyrics = finalLyric,
            extras = mapOf("artistName" to artistName)
        )
        return listOf(lyrics).toFeed()
    }
}