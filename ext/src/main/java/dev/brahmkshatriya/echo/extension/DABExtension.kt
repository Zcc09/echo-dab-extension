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

@Suppress("unused", "UNUSED_PARAMETER")
class DABExtension : ExtensionClient, LoginClient.CustomInput, LibraryFeedClient, PlaylistClient,
    TrackClient, SearchFeedClient, AlbumClient, ArtistClient, LyricsClient, LikeClient, PlaylistEditClient {

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

    @Volatile private var sessionGeneration: Long = 0L
    private fun bumpSessionGeneration() { sessionGeneration++ }

    // --- ExtensionClient ---
    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    override suspend fun getSettingItems(): List<Setting> = emptyList()

    /** Initialize extension and validate existing session */
    override suspend fun onInitialize() {
        val sessionCookie = settings.getString("session_cookie")
        if (sessionCookie != null && currentUser == null) {
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
                settings.putString("session_cookie", null)
            }
        }
    }

    override suspend fun onExtensionSelected() {}

    // --- TrackClient ---
    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track = track

    /** Load streamable media URL for track playback */
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

                if (candidate.startsWith("http", ignoreCase = true) && !candidate.contains("/api/stream")) {
                    return@withContext candidate
                }

                val resolved = api.getStreamUrl(candidate)
                if (!resolved.isNullOrBlank()) return@withContext resolved

                if (candidate.startsWith("http", ignoreCase = true)) candidate
                else "https://dab.yeet.su/api/stream?trackId=${URLEncoder.encode(candidate, "UTF-8")}"
            } catch (_: Throwable) {
                if (candidate.startsWith("http", ignoreCase = true)) candidate
                else "https://dab.yeet.su/api/stream?trackId=${URLEncoder.encode(candidate, "UTF-8")}"
            }
        }

        return streamUrl.toServerMedia()
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

    // --- LyricsClient ---
    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics = lyrics

    /** Search for track lyrics by artist and title */
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
    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        requireAuth()
        return playlist
    }

    /** Load all tracks for a playlist */
    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        requireAuth()
        try {
            val tracks: PagedData<Track> = api.getPlaylistTracks(playlist, 1000)
            val trackList = tracks.loadAll()
            if (trackList.isEmpty()) {
                val directTracks = try {
                    withContext(Dispatchers.IO) {
                        api.fetchAllPlaylistTracks(playlist, 1000)
                    }
                } catch (_: Throwable) {
                    emptyList()
                }
                return directTracks.toFeed()
            }
            return tracks.toFeed()
        } catch (_: Throwable) {
            return emptyList<Track>().toFeed()
        }
    }

    /** Load playlist feed with tracks shelf */
    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? {
        requireAuth()
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
        } catch (_: Throwable) {
            return null
        }
    }

    /** Load search feed with tabs for different content types */
    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        if (query.isBlank()) {
            return emptyList<Shelf>().toFeed()
        }
        return Feed(searchTabs) { tab ->
            when (tab?.id) {
                "all" -> {
                    val pagedAll: PagedData<Shelf> = PagedData.Single {
                        try {
                            val (tracks, albums, artists) = withContext(Dispatchers.IO) {
                                api.searchAll(query, 20)
                            }

                            val shelves = mutableListOf<Shelf>()

                            if (tracks.isNotEmpty()) {
                                shelves.add(
                                    Shelf.Lists.Items(
                                        id = "search_all_tracks",
                                        title = "Tracks",
                                        list = tracks.take(20),
                                        type = Shelf.Lists.Type.Linear
                                    )
                                )
                            }

                            if (albums.isNotEmpty()) {
                                shelves.add(
                                    Shelf.Lists.Items(
                                        id = "search_all_albums",
                                        title = "Albums",
                                        list = albums.take(20),
                                        type = Shelf.Lists.Type.Linear
                                    )
                                )
                            }

                            if (artists.isNotEmpty()) {
                                shelves.add(
                                    Shelf.Lists.Items(
                                        id = "search_all_artists",
                                        title = "Artists",
                                        list = artists.take(20),
                                        type = Shelf.Lists.Type.Linear
                                    )
                                )
                            }

                            // Fallback searches if unified search didn't return albums/artists
                            if (albums.isEmpty() || artists.isEmpty()) {
                                val fallbackAlbums = if (albums.isEmpty()) withContext(Dispatchers.IO) {
                                    api.searchAlbums(query, 10)
                                } else emptyList()
                                val fallbackArtists = if (artists.isEmpty()) withContext(Dispatchers.IO) {
                                    api.searchArtists(query, 10)
                                } else emptyList()
                                if (fallbackAlbums.isNotEmpty()) {
                                    shelves.add(
                                        Shelf.Lists.Items(
                                            id = "search_all_albums_fallback",
                                            title = "Albums",
                                            list = fallbackAlbums,
                                            type = Shelf.Lists.Type.Linear
                                        )
                                    )
                                }
                                if (fallbackArtists.isNotEmpty()) {
                                    shelves.add(
                                        Shelf.Lists.Items(
                                            id = "search_all_artists_fallback",
                                            title = "Artists",
                                            list = fallbackArtists,
                                            type = Shelf.Lists.Type.Linear
                                        )
                                    )
                                }
                            }

                            shelves
                        } catch (_: Throwable) {
                            emptyList()
                        }
                    }
                    Feed.Data(pagedAll)
                }

                "tracks" -> {
                    val rawTracks = try { withContext(Dispatchers.IO) { api.searchTracks(query, 50) } } catch (_: Throwable) { emptyList<Track>() }
                    if (rawTracks.isEmpty()) {
                        Feed.Data(PagedData.empty())
                    } else {
                        val layoutExtras = mapOf(
                            "preferred_layout" to "grid_if_wide",
                            "preferred_padding" to "16",
                            "preferred_item_spacing" to "8"
                        )
                        val modified = rawTracks.map { it.copy(extras = it.extras + layoutExtras) }
                        val pagedTracks: PagedData<Shelf> = PagedData.Single { modified.map { Shelf.Item(it) } }
                        Feed.Data(
                            pagedData = pagedTracks,
                            background = null
                        )
                    }
                }

                "albums" -> {
                    // Present each album as individual shelf items with layout extras similar to tracks
                    val rawAlbums = try { withContext(Dispatchers.IO) { api.searchAlbums(query, 50) } } catch (_: Throwable) { emptyList<Album>() }
                    if (rawAlbums.isEmpty()) {
                        Feed.Data(PagedData.empty())
                    } else {
                        val layoutExtras = mapOf(
                            "preferred_layout" to "grid_if_wide",
                            "preferred_padding" to "16",
                            "preferred_item_spacing" to "8"
                        )
                        val modified = rawAlbums.map { it.copy(extras = it.extras + layoutExtras) }
                        val pagedAlbums: PagedData<Shelf> = PagedData.Single { modified.map { Shelf.Item(it) } }
                        Feed.Data(
                            pagedData = pagedAlbums,
                            buttons = Feed.Buttons(
                                showSearch = true,
                                showSort = true,
                                showPlayAndShuffle = false,
                                customTrackList = null
                            ),
                            background = null
                        )
                    }
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
                        } catch (_: Throwable) {
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
    /** Load complete album details including tracks */
    override suspend fun loadAlbum(album: Album): Album {
        val dabAlbum = withContext(Dispatchers.IO) {
            api.getAlbum(album.id)
        }

        val result = dabAlbum?.let { converter.toAlbum(it) } ?: album
        return result
    }

    /** Load all tracks for an album */
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

    /** Load album feed with tracks shelf */
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
    /** Load artist details */
    override suspend fun loadArtist(artist: Artist): Artist {
        val dabArtist = withContext(Dispatchers.IO) {
            api.getArtistDetails(artist.id)
        }

        val result = dabArtist?.let { converter.toArtist(it) } ?: artist
        return result
    }

    /** Load artist feed with discography */
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

    /** Handle user login and save session */
    override suspend fun onLogin(key: String, data: Map<String, String?>): List<User> {
        val email = data["email"] ?: error("Email is required")
        val password = data["password"] ?: error("Password is required")
        val user = withContext(Dispatchers.IO) { api.loginAndSaveCookie(email, password) }
        currentUser = user
        bumpSessionGeneration() // invalidate any in-flight feed tasks from previous session
        return listOf(user)
    }

    /** Set or clear current user */
    override fun setLoginUser(user: User?) {
        if (user == null) {
            // Synchronously clear session & caches before generation bump to avoid stale reuse
            try { api.clearSession() } catch (_: Throwable) {}
            settings.putString("session_cookie", null)
            currentUser = null
            bumpSessionGeneration() // ensure any in-flight tasks won't publish
            try { api.shutdown() } catch (_: Throwable) {}
        } else {
            currentUser = user
            bumpSessionGeneration()
        }
    }

    /** Get current authenticated user */
    override suspend fun getCurrentUser(): User? {
        if (currentUser != null && api.hasValidSession()) {
            return currentUser
        }

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
                settings.putString("session_cookie", null)
            }
        }

        return currentUser
    }

    // Added helper to centralize auth requirement
    private suspend fun requireAuth() {
        if (getCurrentUser() == null || !api.hasValidSession()) throw ClientException.LoginRequired()
    }

    /** Load playlists list (no shelves) */
    private suspend fun loadPlaylists(gen: Long = sessionGeneration): List<Playlist>? {
        if (gen != sessionGeneration) return null
        if (!api.hasValidSession()) throw ClientException.LoginRequired()
        val pls = try { withContext(Dispatchers.IO) { api.fetchLibraryPlaylistsPage(1, 100) } } catch (_: Throwable) { emptyList<Playlist>() }
        if (gen != sessionGeneration) return null
        val base = pls.takeIf { it.isNotEmpty() } ?: return null
        // Fallback: attempt to populate missing covers by fetching a few tracks
        val enhanced = base.map { p ->
            if (gen != sessionGeneration) return null
            if (p.cover != null) p else {
                val tracksForCover = try {
                    withContext(Dispatchers.IO) { api.fetchAllPlaylistTracks(p, 5) }
                } catch (_: Throwable) { emptyList() }
                if (gen != sessionGeneration) return null
                val coverTrack = tracksForCover.firstOrNull { it.cover != null }
                if (coverTrack != null) p.copy(cover = coverTrack.cover) else p
            }
        }.filterNotNull()
        if (gen != sessionGeneration) return null
        return enhanced
    }

    // --- Helper to build Feed.Data from shelves with proper empty handling
    private fun shelvesToFeedData(shelves: List<Shelf>): Feed.Data<Shelf> {
        return if (shelves.isEmpty()) {
            Feed.Data(
                pagedData = PagedData.empty(),
                buttons = Feed.Buttons.EMPTY,
                background = null
            )
        } else {
            Feed.Data(
                pagedData = PagedData.Single { shelves },
                buttons = null,
                background = null
            )
        }
    }

    // --- LibraryFeedClient ---
    /** Load user's library feed with tabs for different content types */
    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        val user = getCurrentUser()
        if (user == null || !api.hasValidSession()) throw ClientException.LoginRequired()
        val genAtStart = sessionGeneration
        return Feed(libraryTabs) { tab ->
            // Re-check auth & generation every invocation
            if (genAtStart != sessionGeneration) {
                if (getCurrentUser() == null || !api.hasValidSession()) throw ClientException.LoginRequired()
            }
            if (getCurrentUser() == null || !api.hasValidSession()) throw ClientException.LoginRequired()
            when (tab?.id) {
                "all" -> {
                    val playlists = try { loadPlaylists(genAtStart) } catch (e: ClientException.LoginRequired) { throw e } catch (_: Throwable) { null }
                    val favorites = try { withContext(Dispatchers.IO) { api.getFavoritesAuthenticated() } } catch (_: Throwable) { emptyList<Track>() }
                    if (genAtStart != sessionGeneration) { if (getCurrentUser() == null || !api.hasValidSession()) throw ClientException.LoginRequired() }
                    val shelves = buildList {
                        if (!playlists.isNullOrEmpty()) add(
                            Shelf.Lists.Items(
                                id = "library_playlists_all",
                                title = "Playlists",
                                list = playlists,
                                type = Shelf.Lists.Type.Linear
                            )
                        )
                        if (favorites.isNotEmpty()) add(
                            Shelf.Lists.Tracks(
                                id = "favorites_tracks_all",
                                title = "Favorites",
                                list = favorites
                            )
                        )
                    }
                    shelvesToFeedData(shelves)
                }
                "playlists" -> {
                    val playlists = try { loadPlaylists(genAtStart) } catch (e: ClientException.LoginRequired) { throw e } catch (_: Throwable) { null }
                    if (genAtStart != sessionGeneration) { if (getCurrentUser() == null || !api.hasValidSession()) throw ClientException.LoginRequired() }
                    val shelves = if (playlists.isNullOrEmpty()) emptyList() else listOf(
                        Shelf.Lists.Items(
                            id = "library_playlists_tab",
                            title = "Playlists",
                            list = playlists,
                            type = Shelf.Lists.Type.Linear
                        )
                    )
                    shelvesToFeedData(shelves)
                }
                "tracks" -> {
                    val favs = try { withContext(Dispatchers.IO) { api.getFavoritesAuthenticated() } } catch (_: Throwable) { emptyList<Track>() }
                    if (genAtStart != sessionGeneration) { if (getCurrentUser() == null || !api.hasValidSession()) throw ClientException.LoginRequired() }
                    if (favs.isEmpty()) {
                        shelvesToFeedData(emptyList())
                    } else {
                        val layoutExtras = mapOf(
                            "preferred_layout" to "grid_if_wide",
                            "preferred_padding" to "16",
                            "preferred_item_spacing" to "8"
                        )
                        val modifiedTracks = favs.map { it.copy(extras = it.extras + layoutExtras) }
                        val paged: PagedData<Shelf> = PagedData.Single { modifiedTracks.map { Shelf.Item(it) } }
                        Feed.Data(
                            pagedData = paged,
                            buttons = Feed.Buttons(
                                showSearch = true,
                                showSort = true,
                                showPlayAndShuffle = true,
                                customTrackList = modifiedTracks
                            ),
                            background = null
                        )
                    }
                }
                else -> shelvesToFeedData(emptyList())
            }
        }
    }

    // --- LikeClient ---
    /** Add or remove track from favorites */
    override suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean) {
        requireAuth()
        val track = when (item) {
            is Track -> item
            else -> return
        }
        withContext(Dispatchers.IO) {
            if (shouldLike) {
                api.addFavorite(track)
            } else {
                api.removeFavorite(track.id)
            }
        }
    }

    /** Check if track is liked/favorited */
    override suspend fun isItemLiked(item: EchoMediaItem): Boolean {
        requireAuth()
        val track = when (item) {
            is Track -> item
            else -> return false
        }
        return withContext(Dispatchers.IO) {
            api.isTrackFavorite(track.id)
        }
    }

    // --- PlaylistEditClient ---
    override suspend fun listEditablePlaylists(track: Track?): List<Pair<Playlist, Boolean>> {
        if (getCurrentUser() == null || !api.hasValidSession()) throw ClientException.LoginRequired()
        val playlists = withContext(Dispatchers.IO) { api.fetchLibraryPlaylistsPage(1, 200) }
        val trackId = track?.id
        return playlists.filter { it.isEditable }.map { p ->
            if (trackId == null) p to false else {
                // Load tracks only if needed to check membership; use cached paged fetch
                val contains = try {
                    val list = withContext(Dispatchers.IO) { api.fetchAllPlaylistTracks(p, 1000) }
                    list.any { it.id == trackId }
                } catch (_: Throwable) { false }
                p to contains
            }
        }
    }

    override suspend fun createPlaylist(title: String, description: String?): Playlist {
        if (title.isBlank()) error("Title required")
        if (getCurrentUser() == null || !api.hasValidSession()) throw ClientException.LoginRequired()
        return withContext(Dispatchers.IO) { api.createPlaylist(title.trim(), description?.trim()) }
    }

    override suspend fun deletePlaylist(playlist: Playlist) {
        if (getCurrentUser() == null || !api.hasValidSession()) throw ClientException.LoginRequired()
        withContext(Dispatchers.IO) { api.deletePlaylist(playlist) }
    }

    override suspend fun editPlaylistMetadata(playlist: Playlist, title: String, description: String?) {
        if (getCurrentUser() == null || !api.hasValidSession()) throw ClientException.LoginRequired()
        withContext(Dispatchers.IO) { api.editPlaylistMetadata(playlist, title.trim(), description?.trim()) }
    }

    override suspend fun addTracksToPlaylist(
        playlist: Playlist, tracks: List<Track>, index: Int, new: List<Track>
    ) {
        if (getCurrentUser() == null || !api.hasValidSession()) throw ClientException.LoginRequired()
        // API doesn't support ordering by index; just append
        withContext(Dispatchers.IO) { api.addTracksToPlaylist(playlist, new) }
    }

    override suspend fun removeTracksFromPlaylist(
        playlist: Playlist, tracks: List<Track>, indexes: List<Int>
    ) {
        if (getCurrentUser() == null || !api.hasValidSession()) throw ClientException.LoginRequired()
        withContext(Dispatchers.IO) { api.removeTracksFromPlaylist(playlist, tracks, indexes) }
    }

    override suspend fun moveTrackInPlaylist(
        playlist: Playlist, tracks: List<Track>, fromIndex: Int, toIndex: Int
    ) {

    }
}
