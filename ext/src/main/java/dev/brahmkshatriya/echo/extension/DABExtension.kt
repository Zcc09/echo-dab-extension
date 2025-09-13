package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.*
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toServerMedia
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import java.net.URLEncoder
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.async
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toResourceUriImageHolder

class DABExtension : ExtensionClient, LoginClient.CustomInput, LibraryFeedClient, PlaylistClient,
    TrackClient, SearchFeedClient, AlbumClient, ArtistClient, LyricsClient, LikeClient {

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
    private val api: DABApi by lazy {
        DABApi(client, converter, settings).also {
            converter.api = it
        }
    }
    private var currentUser: User? = null

    private val libraryTabs = listOf(
        Tab(id = "all", title = "All"),
        Tab(id = "playlists", title = "Playlists"),
        Tab(id = "tracks", title = "Tracks")
    )

    private val searchTabs = listOf(
        Tab(id = "all", title = "All"),
        Tab(id = "tracks", title = "Tracks"),
        Tab(id = "albums", title = "Albums"),
        Tab(id = "artists", title = "Artists")
    )

    // --- ExtensionClient ---
    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    override suspend fun getSettingItems(): List<Setting> = emptyList()

    override suspend fun onInitialize() {
        val sessionCookie = settings.getString("session_cookie")
        if (sessionCookie != null && currentUser == null) {
            // Validate the session is still active
            currentUser = withContext(Dispatchers.IO) {
                runCatching {
                    if (api.hasValidSession()) {
                        api.getMe()
                    } else {
                        null
                    }
                }.getOrNull()
            }
            if (currentUser == null) {
                // Clear invalid session
                settings.putString("session_cookie", null)
            }
        }
    }

    override suspend fun onExtensionSelected() {}

    // --- TrackClient ---
    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track = track

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean
    ): Streamable.Media {
        val candidate = streamable.extras.values.firstNotNullOfOrNull { it }
            ?: error("Track ID not found in extras")

        val streamUrl = withContext(Dispatchers.IO) {
            try {
                val cachedUrl = api.getCachedStreamUrl(candidate)
                if (!cachedUrl.isNullOrBlank()) return@withContext cachedUrl

                if (candidate.startsWith(
                        "http",
                        ignoreCase = true
                    ) && !candidate.contains("/api/stream")
                ) {
                    return@withContext candidate
                }

                // Try stream resolution
                val resolved = api.getStreamUrl(candidate)
                if (!resolved.isNullOrBlank()) return@withContext resolved

                // Fallback
                if (candidate.startsWith("http", ignoreCase = true)) candidate
                else "https://dab.yeet.su/api/stream?trackId=${
                    URLEncoder.encode(
                        candidate,
                        "UTF-8"
                    )
                }"
            } catch (_: Throwable) {
                if (candidate.startsWith("http", ignoreCase = true)) candidate
                else "https://dab.yeet.su/api/stream?trackId=${
                    URLEncoder.encode(
                        candidate,
                        "UTF-8"
                    )
                }"
            }
        }

        return streamUrl.toServerMedia()
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

    // --- LyricsClient ---
    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics = lyrics

    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> {
        val artist = track.artists.firstOrNull()?.name ?: return emptyList<Lyrics>().toFeed()
        val lyricsText = withContext(Dispatchers.IO) {
            api.getLyrics(artist, track.title)
        }

        if (lyricsText.isNullOrBlank()) return emptyList<Lyrics>().toFeed()

        val convertedLyrics = converter.toLyricFromText(lyricsText)
            ?: Lyrics.Simple(converter.cleanPlainText(lyricsText))

        return listOf(
            Lyrics(
                id = "${track.id}_lyrics",
                title = "Lyrics for ${track.title}",
                subtitle = artist,
                lyrics = convertedLyrics
            )
        ).toFeed()
    }


    // --- PlaylistClient ---
    override suspend fun loadPlaylist(playlist: Playlist): Playlist = playlist

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        try {
            val tracks: PagedData<Track> = api.getPlaylistTracks(playlist, 1000)
            val trackList = tracks.loadAll()
            if (trackList.isEmpty()) {
                // Try direct fetch as fallback
                val directTracks = try {
                    withContext(Dispatchers.IO) {
                        api.fetchAllPlaylistTracks(playlist, 1000)
                    }
                } catch (e: Throwable) {
                    emptyList()
                }
                return directTracks.toFeed()
            }
            return tracks.toFeed()
        } catch (e: Throwable) {
            return emptyList<Track>().toFeed()
        }
    }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? {
        try {
            val tracksList = try {
                withContext(Dispatchers.IO) {
                    api.fetchAllPlaylistTracks(playlist, 1000)
                }
            } catch (_: Throwable) {
                emptyList<Track>()
            }

            val shelves = mutableListOf<Shelf>()
            if (tracksList.isNotEmpty()) {
                shelves.add(
                    Shelf.Lists.Tracks(
                        id = "playlist_tracks_${playlist.id}",
                        title = "Tracks",
                        list = tracksList
                    )
                )
            }
            return if (shelves.isNotEmpty()) shelves.toFeed() else null
        } catch (e: Throwable) {
            return null
        }
    }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        if (query.isBlank()) {
            return emptyList<Shelf>().toFeed()
        }
        return Feed(searchTabs) { tab ->
            when (tab?.id) {
                "all" -> {
                    val pagedAll: PagedData<Shelf> = PagedData.Single {
                        try {

                            // Use unified search endpoint for all types
                            val (tracks, albums, artists) = withContext(Dispatchers.IO) {
                                api.searchAll(query, 20)
                            }

                            val shelves = mutableListOf<Shelf>()

                            // Add tracks shelf if we have results
                            if (tracks.isNotEmpty()) {
                                shelves.add(
                                    Shelf.Lists.Items(
                                        id = "search_all_tracks",
                                        title = "Tracks",
                                        list = tracks.take(20),
                                        type = Shelf.Lists.Type.Grid // Limit for "All" view
                                    )
                                )
                            }

                            // Add albums shelf if we have results
                            if (albums.isNotEmpty()) {
                                shelves.add(
                                    Shelf.Lists.Items(
                                        id = "search_all_albums",
                                        title = "Albums",
                                        list = albums.take(20), // Limit for "All" view
                                        type = Shelf.Lists.Type.Grid
                                    )
                                )
                            }

                            // Add artists shelf if we have results
                            if (artists.isNotEmpty()) {
                                shelves.add(
                                    Shelf.Lists.Items(
                                        id = "search_all_artists",
                                        title = "Artists",
                                        list = artists.take(20), // Limit for "All" view
                                        type = Shelf.Lists.Type.Grid
                                    )
                                )
                            }

                            // Fallback: If albums/artists are empty, run separate searches
                            if (albums.isEmpty() || artists.isEmpty()) {
                                val fallbackAlbums =
                                    if (albums.isEmpty()) withContext(Dispatchers.IO) {
                                        api.searchAlbums(
                                            query,
                                            10
                                        )
                                    } else emptyList()
                                val fallbackArtists =
                                    if (artists.isEmpty()) withContext(Dispatchers.IO) {
                                        api.searchArtists(
                                            query,
                                            10
                                        )
                                    } else emptyList()
                                if (fallbackAlbums.isNotEmpty()) {
                                    shelves.add(
                                        Shelf.Lists.Items(
                                            id = "search_all_albums_fallback",
                                            title = "Albums",
                                            list = fallbackAlbums,
                                            type = Shelf.Lists.Type.Grid
                                        )
                                    )
                                }
                                if (fallbackArtists.isNotEmpty()) {
                                    shelves.add(
                                        Shelf.Lists.Items(
                                            id = "search_all_artists_fallback",
                                            title = "Artists",
                                            list = fallbackArtists,
                                            type = Shelf.Lists.Type.Grid
                                        )
                                    )
                                }
                            }

                            shelves
                        } catch (e: Throwable) {
                            emptyList()
                        }
                    }
                    Feed.Data(pagedAll)
                }

                "tracks" -> {
                    val pagedTracks: PagedData<Shelf> = PagedData.Single {
                        try {
                            val tracks = withContext(Dispatchers.IO) {
                                api.searchTracks(query, 50)
                            }
                            if (tracks.isEmpty()) {
                                emptyList()
                            } else {
                                // List each track as a Shelf.Item, just like the library tracks tab
                                tracks.map { Shelf.Item(it) }
                            }
                        } catch (e: Throwable) {
                            emptyList()
                        }
                    }
                    Feed.Data(pagedTracks)
                }

                "albums" -> {
                    val pagedAlbums: PagedData<Shelf> = PagedData.Single {
                        try {
                            val albums = withContext(Dispatchers.IO) {
                                api.searchAlbums(query, 20)
                            }

                            if (albums.isEmpty()) {
                                emptyList()
                            } else {
                                listOf(
                                    Shelf.Lists.Items(
                                        id = "search_albums",
                                        title = "Results",
                                        list = albums,
                                        type = Shelf.Lists.Type.Grid
                                    )
                                )
                            }
                        } catch (e: Throwable) {
                            emptyList()
                        }
                    }
                    Feed.Data(pagedAlbums)
                }

                "artists" -> {
                    val pagedArtists: PagedData<Shelf> = PagedData.Single {
                        try {
                            val artists = withContext(Dispatchers.IO) {
                                api.searchArtists(query, 50)
                            }

                            if (artists.isEmpty()) {
                                emptyList()
                            } else {
                                listOf(
                                    Shelf.Lists.Items(
                                        id = "search_artists",
                                        title = "Results",
                                        list = artists,
                                        type = Shelf.Lists.Type.Grid
                                    )
                                )
                            }
                        } catch (e: Throwable) {
                            emptyList()
                        }
                    }
                    Feed.Data(pagedArtists)
                }

                else -> emptyList<Shelf>().toFeedData()
            }
        }
    }

    // --- AlbumClient ---
    override suspend fun loadAlbum(album: Album): Album {
        val dabAlbum = withContext(Dispatchers.IO) {
            api.getAlbum(album.id)
        }

        val result = dabAlbum?.let { converter.toAlbum(it) } ?: album
        return result
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? {
        val dabAlbum = withContext(Dispatchers.IO) {
            api.getAlbum(album.id)
        }

        val tracks = dabAlbum?.tracks?.map { converter.toTrack(it) }
        if (tracks.isNullOrEmpty()) {
            return null
        }
        return tracks.toFeed()
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? {
        val dabAlbum = withContext(Dispatchers.IO) {
            api.getAlbum(album.id)
        }

        val tracks = dabAlbum?.tracks?.map { converter.toTrack(it) }
        if (tracks.isNullOrEmpty()) {
            return null
        }
        val shelves = listOf(
            Shelf.Lists.Tracks(
                id = "album_tracks_${album.id}",
                title = "Tracks",
                list = tracks
            )
        )
        return shelves.toFeed()
    }

    // --- ArtistClient ---
    override suspend fun loadArtist(artist: Artist): Artist {
        val dabArtist = withContext(Dispatchers.IO) {
            api.getArtistDetails(artist.id)
        }

        val result = dabArtist?.let { converter.toArtist(it) } ?: artist
        return result
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val albums = withContext(Dispatchers.IO) {
            api.getArtistDiscography(artist.id)
        }

        if (albums.isEmpty()) {
            return emptyList<Shelf>().toFeed()
        }
        val albumItems = albums.map { converter.toAlbum(it) }
        val shelves = listOf(
            Shelf.Lists.Items(
                id = "artist_albums_${artist.id}",
                title = "Albums",
                list = albumItems,
                type = Shelf.Lists.Type.Grid
            )
        )
        return shelves.toFeed()
    }

    // --- LoginClient ---
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

    override fun setLoginUser(user: User?) {
        if (user == null) {
            // Clear all session data and reset state
            settings.putString("session_cookie", null)
            currentUser = null
            api.clearSession()
            try {
                api.shutdown()
            } catch (_: Throwable) {
            }
        } else {
            currentUser = user
        }
    }

    override suspend fun getCurrentUser(): User? {
        // First check if we have a cached user and valid session
        if (currentUser != null && api.hasValidSession()) {
            return currentUser
        }

        // Try to initialize from stored session
        val sessionCookie = settings.getString("session_cookie")
        if (!sessionCookie.isNullOrBlank()) {
            currentUser = withContext(Dispatchers.IO) {
                runCatching {
                    if (api.hasValidSession()) {
                        api.getMe()
                    } else {
                        null
                    }
                }.getOrNull()
            }
            if (currentUser == null) {
                // Clear invalid session
                settings.putString("session_cookie", null)
            }
        }

        return currentUser
    }

    private suspend fun loadPlaylistShelves(): List<Shelf>? {
        if (!api.hasValidSession()) return null
        val pls = try {
            withContext(Dispatchers.IO) { api.fetchLibraryPlaylistsPage(1, 50) }
        } catch (_: Throwable) {
            emptyList<Playlist>()
        }
        if (pls.isEmpty()) return null

        return pls.map { playlist ->
            val playlistWithCover = try {
                if (playlist.cover != null) {
                    playlist
                } else {
                    val tracksForCover = try {
                        withContext(Dispatchers.IO) {
                            api.fetchAllPlaylistTracks(playlist, 3)
                        }
                    } catch (e: Throwable) {
                        emptyList<Track>()
                    }
                    if (tracksForCover.isNotEmpty()) {
                        val trackWithCover = tracksForCover.firstOrNull { it.cover != null }
                        if (trackWithCover != null) {
                            playlist.copy(cover = trackWithCover.cover)
                        } else {
                            try {
                                playlist.copy(cover = "file:///android_asset/ext/icon.png".toResourceUriImageHolder())
                            } catch (_: Throwable) {
                                playlist
                            }
                        }
                    } else {
                        try {
                            playlist.copy(cover = "file:///android_asset/ext/icon.png".toResourceUriImageHolder())
                        } catch (_: Throwable) {
                            playlist
                        }
                    }
                }
            } catch (e: Throwable) {
                playlist
            }
            Shelf.Item(playlistWithCover)
        }
    }

    private suspend fun loadFavoritesShelves(): List<Shelf>? {
        if (!api.hasValidSession()) return null
        val favs = try { withContext(Dispatchers.IO) { api.getFavoritesAuthenticated() } } catch (_: Throwable) { emptyList<Track>() }
        if (favs.isEmpty()) return null
        return favs.map { Shelf.Item(it) }
    }

    private suspend fun loadFavoritesShelvesRaw(): List<Track>? {
        if (!api.hasValidSession()) return null
        val favs = try { withContext(Dispatchers.IO) { api.getFavoritesAuthenticated() } } catch (_: Throwable) { emptyList<Track>() }
        if (favs.isEmpty()) return null

        return favs
    }

    // --- LibraryFeedClient ---
    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        // Check authentication first
        val user = getCurrentUser()
        if (user == null || !api.hasValidSession()) {
            throw ClientException.LoginRequired()
        }

        return Feed(libraryTabs) { tab ->
            // Double-check authentication for each tab load
            if (getCurrentUser() == null || !api.hasValidSession()) {
                throw ClientException.LoginRequired()
            }

            when (tab?.id) {
                "all" -> {
                    supervisorScope {
                        val deferreds = libraryTabs.map { t ->
                            async(Dispatchers.Default) {
                                when (t.id) {
                                    "playlists" -> loadPlaylistShelves()
                                    "tracks" -> {
                                        // For the 'all' tab, show favorites as a single grid shelf of tracks
                                        val favs = loadFavoritesShelvesRaw()
                                        if (favs != null && favs.isNotEmpty()) {
                                            listOf(
                                                Shelf.Lists.Tracks(
                                                    id = "favorites_tracks_grid",
                                                    title = "Favorites",
                                                    list = favs,
                                                    type = Shelf.Lists.Type.Grid
                                                )
                                            )
                                        } else null
                                    }
                                    else -> null
                                }
                            }
                        }
                        deferreds.mapNotNull { it.await() }.flatten()
                    }.toFeedData()
                }

                "playlists" -> {
                    val shelves = try {
                        withContext(Dispatchers.IO) { loadPlaylistShelves() }
                    } catch (_: Throwable) {
                        null
                    }
                    (shelves ?: emptyList()).toFeedData()
                }

                "tracks" -> {
                    val shelves = try {
                        withContext(Dispatchers.IO) { loadFavoritesShelves() }
                    } catch (_: Throwable) {
                        null
                    }
                    (shelves ?: emptyList()).toFeedData()
                }

                else -> emptyList<Shelf>().toFeedData()
            }
        }
    }

    // --- LikeClient ---
    override suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean) {
        // Only support Track for now
        val track = when (item) {
            is Track -> item
            else -> return // Only tracks are likeable in DAB
        }
        withContext(Dispatchers.IO) {
            if (shouldLike) {
                api.addFavorite(track)
            } else {
                api.removeFavorite(track.id)
            }
        }
    }

    override suspend fun isItemLiked(item: EchoMediaItem): Boolean {
        val track = when (item) {
            is Track -> item
            else -> return false
        }
        return withContext(Dispatchers.IO) {
            api.isTrackFavorite(track.id)
        }
    }
}
