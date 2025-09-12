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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.brahmkshatriya.echo.common.helpers.Page
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import dev.brahmkshatriya.echo.common.models.Tab

class DABExtension : ExtensionClient, LoginClient.CustomInput, LibraryFeedClient, PlaylistClient,
    TrackClient, SearchFeedClient, AlbumClient, ArtistClient, LyricsClient {

    private lateinit var settings: Settings
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val converter by lazy { Converter() }
    private val api: DABApi by lazy { DABApi(client, converter, settings).also { converter.api = it } }
    private var currentUser: User? = null

    // Prefer direct API calls with runCatching wrappers for safety and performance.
    // Suspend helpers that execute blocking API calls on the IO dispatcher
    private suspend fun getAlbumSafe(albumId: String): dev.brahmkshatriya.echo.extension.models.DabAlbum? {
        return try {
            withContext(Dispatchers.IO) { api.getAlbum(albumId) }
        } catch (_: Throwable) { null }
    }

    private suspend fun getLyricsSafe(artist: String, title: String): String? {
        return try {
            withContext(Dispatchers.IO) { api.getLyrics(artist, title) }
        } catch (_: Throwable) { null }
    }

    // From ExtensionClient
    override fun setSettings(settings: Settings) {
        this.settings = settings
        // No diagnostics in production; store settings silently
    }

    override suspend fun getSettingItems(): List<Setting> {

        return emptyList()
    }

    override suspend fun onInitialize() {
        val sessionCookie = settings.getString("session_cookie")
        if (sessionCookie != null && currentUser == null) {
            currentUser = withContext(Dispatchers.IO) { runCatching { api.getMe() }.getOrNull() }
            if (currentUser == null) {
                settings.putString("session_cookie", null)
            }
            // If we have a logged in user, we no longer prefetch full favorites (avoid costly operations)
        }
    }

    override suspend fun onExtensionSelected() {
    }

    override val forms: List<LoginClient.Form>
        get() = listOf(
            LoginClient.Form(
                key = "login_form",
                label = "DAB Music Login",
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
        val user = withContext(Dispatchers.IO) { api.loginAndSaveCookie(email, password) }
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


        // Fetch playlists and a small probe of favorites sequentially (simpler and robust)
        val playlistsList = try { withContext(Dispatchers.IO) { api.fetchLibraryPlaylistsPage(1, 50) } } catch (_: Throwable) { emptyList<Playlist>() }
        val firstFavoritesProbe = try { withContext(Dispatchers.IO) { api.getFavorites() } } catch (_: Throwable) { emptyList<Track>() }

        val playlistsPaged = PagedData.Single<Shelf> {
            if (playlistsList.isEmpty()) emptyList()
            else listOf(Shelf.Lists.Items(
                id = "my_playlists",
                title = "Playlists",
                list = playlistsList,
                type = Shelf.Lists.Type.Grid
            ))
        }


        val favoritesHeader = PagedData.Single<Shelf> {
            listOf(Shelf.Category(id = "favorites_header", title = "Favorites"))
        }


        val favoritesItemsForAll: PagedData<Shelf> = PagedData.Single {
            val favs = if (firstFavoritesProbe.isNotEmpty()) firstFavoritesProbe
            else try { withContext(Dispatchers.IO) { api.fetchFavoritesPage(1, 50) } } catch (_: Throwable) { emptyList<Track>() }

            if (favs.isNotEmpty()) listOf(Shelf.Lists.Items(id = "favorites_tracks_items", title = "♥ Favorites", list = favs, type = Shelf.Lists.Type.Linear))
            else listOf(Shelf.Category(id = "no_favorites_config", title = "Favorites not found", extras = mapOf("hint" to "Your server did not return favorites.")))
        }

        val tracksWithHeaderForAll = PagedData.Concat(favoritesHeader, favoritesItemsForAll)


        val allPaged: PagedData<Shelf> = if (firstFavoritesProbe.isNotEmpty()) {
            PagedData.Single {
                val shelves = mutableListOf<Shelf>()
                if (playlistsList.isNotEmpty()) {
                    shelves.add(Shelf.Lists.Items(id = "my_playlists", title = "Playlists", list = playlistsList, type = Shelf.Lists.Type.Grid))
                }
                shelves.add(Shelf.Category(id = "favorites_header", title = "Favorites"))
                shelves.add(Shelf.Lists.Items(id = "favorites_tracks_items", title = "♥ Favorites", list = firstFavoritesProbe, type = Shelf.Lists.Type.Linear))
                shelves
            }
        } else {
            PagedData.Concat(playlistsPaged, tracksWithHeaderForAll)
        }

        val tabs: List<Tab> = listOf(
            Tab(id = "all", title = "All"),
            Tab(id = "playlists", title = "Playlists"),
            Tab(id = "tracks", title = "Tracks")
        )

        return Feed(tabs) { tab ->
            when (tab?.id) {
                "all" -> Feed.Data(allPaged)
                "playlists" -> Feed.Data(playlistsPaged)
                "tracks" -> {
                    val pageSize = 50
                    val pagedShelves: PagedData<Shelf> = PagedData.Continuous { continuation ->
                        val pageNum = continuation?.toIntOrNull() ?: 1
                        val pageList = try { withContext(Dispatchers.IO) { api.fetchFavoritesPage(pageNum, pageSize) } } catch (_: Throwable) { emptyList<Track>() }
                        // Map each Track to a Shelf.Item so the UI renders them as individual rows
                        val shelfItems = pageList.map { t -> Shelf.Item(t) }
                        Page(shelfItems, if (pageList.size >= pageSize) (pageNum + 1).toString() else null)
                    }

                    Feed.Data(pagedShelves, Feed.Buttons(showSearch = false, showSort = false, showPlayAndShuffle = true))
                }
                else -> Feed.Data(allPaged)
            }
        }
    }

    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        return playlist
    }

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        // Load tracks for a playlist using paged API so the UI can request pages incrementally.
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
        val candidate = streamable.extras["url"]
            ?: streamable.extras["stream_url"]
            ?: streamable.extras["dab_id"]
            ?: streamable.extras["id"]
            ?: error("Track ID not found in extras")

        val streamUrl = withContext(Dispatchers.IO) {
              try {
                // 1) If we already have a cached resolved CDN URL (in-memory or persisted), use it immediately
                val cached = try { api.getCachedStreamUrl(candidate.takeIf { !it.startsWith("http", true) } ?: (candidate.substringAfter("trackId=").substringBefore('&'))) } catch (_: Throwable) { null }
                if (!cached.isNullOrBlank()) return@withContext cached

                if (candidate.startsWith("http", ignoreCase = true)) {
                     // If it's a full URL that points to the DAB API stream endpoint, try to resolve
                     // it to a concrete media URL. If it's already a CDN URL, use it directly.
                     if (candidate.contains("/api/stream")) {
                         // Prefer extracting trackId from the query string and using getStreamUrl
                         val tid = try {
                             candidate.substringAfter("trackId=").substringBefore('&')
                         } catch (_: Exception) { "" }

                         if (tid.isNotBlank()) {
                            // Try a fast single-attempt resolver first to improve first-play latency
                            val quick = try { api.quickResolveStreamUrl(tid, 1000L) } catch (_: Throwable) { null }
                            if (!quick.isNullOrBlank()) quick else api.getStreamUrl(tid)
                         } else {
                             api.resolveApiStreamEndpoint(candidate) ?: candidate
                         }
                     } else {
                         candidate
                     }
                 } else {
                    // Treat as a plain track id. Try a fast one-shot resolver before the full resolution
                    val quick = try { api.quickResolveStreamUrl(candidate, 1000L) } catch (_: Throwable) { null }
                    if (!quick.isNullOrBlank()) quick else api.getStreamUrl(candidate)
                 }
             } catch (e: Throwable) {
                 // Surface a helpful error when resolution fails
                 throw IllegalStateException("Failed to resolve stream URL for candidate=$candidate", e)
             }
         }

        val resolvedUrl = streamUrl ?: throw IllegalStateException("Stream URL resolution returned null for candidate=$candidate")
        return resolvedUrl.toServerMedia()
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? {
        return null
    }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        if (query.isBlank()) {
            return emptyList<Shelf>().toFeed()
        }

        val shelves = mutableListOf<Shelf>()

        // Use the combined concurrent cached search helper to fetch tracks, albums and artists
        val (tracks, albums, artists) = try {
            val res = withContext(Dispatchers.IO) { api.searchAll(query, 8, 4, 4) }
            Triple(res.tracks, res.albums, res.artists)
        } catch (_: Throwable) {
            Triple(emptyList<Track>(), emptyList<dev.brahmkshatriya.echo.common.models.Album>(), emptyList<dev.brahmkshatriya.echo.common.models.Artist>())
        }

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

        // Add album shelf (grid) if there are album results
        if (albums.isNotEmpty()) {
            shelves.add(
                Shelf.Lists.Items(
                    id = "search_albums",
                    title = "💿 Albums",
                    list = albums,
                    type = Shelf.Lists.Type.Grid
                )
            )
        }

        // Add artist shelf (linear list) if there are artist results
        if (artists.isNotEmpty()) {
            shelves.add(
                Shelf.Lists.Items(
                    id = "search_artists",
                    title = "👤 Artists",
                    list = artists,
                    type = Shelf.Lists.Type.Linear
                )
            )
        }

        return shelves.toFeed()
    }

    // --- AlbumClient ---
    override suspend fun loadAlbum(album: dev.brahmkshatriya.echo.common.models.Album): dev.brahmkshatriya.echo.common.models.Album {
        val dab = getAlbumSafe(album.id)
        return dab?.let { converter.toAlbum(it) } ?: album
    }
    override suspend fun loadTracks(album: dev.brahmkshatriya.echo.common.models.Album): Feed<Track> {
        val dab = getAlbumSafe(album.id)
        val tracks = dab?.tracks?.map { converter.toTrack(it) } ?: emptyList()
        return tracks.toFeed()
    }
    override suspend fun loadFeed(album: dev.brahmkshatriya.echo.common.models.Album): Feed<Shelf>? {
        val dabAlbum = getAlbumSafe(album.id) ?: return null
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
            val albumWithTracks = getAlbumSafe(firstAlbum.id)
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
                type = Shelf.Lists.Type.Grid
            ))
        }

        return shelves.toFeed()
    }

    // --- LyricsClient ---
    override suspend fun loadLyrics(lyrics: dev.brahmkshatriya.echo.common.models.Lyrics): dev.brahmkshatriya.echo.common.models.Lyrics {
        val artist = lyrics.extras["artistName"] ?: return lyrics
        val lyricsText = try { getLyricsSafe(artist, lyrics.title) } catch (_: Throwable) { null }

        if (lyricsText.isNullOrBlank()) return lyrics.copy(lyrics = dev.brahmkshatriya.echo.common.models.Lyrics.Simple(""))

        val lyricObj = try { converter.toLyricFromText(lyricsText) } catch (_: Throwable) { null }
        if (lyricObj != null) return lyrics.copy(lyrics = lyricObj)

        val fallback = dev.brahmkshatriya.echo.common.models.Lyrics.Simple(converter.cleanPlainText(lyricsText))
        return lyrics.copy(lyrics = fallback)
    }
    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<dev.brahmkshatriya.echo.common.models.Lyrics> {
        // searchTrackLyrics invoked
        val artistName = track.artists.firstOrNull()?.name ?: return emptyList<dev.brahmkshatriya.echo.common.models.Lyrics>().toFeed()
        val lyricsText = try { getLyricsSafe(artistName, track.title) } catch (_: Throwable) { null }

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
