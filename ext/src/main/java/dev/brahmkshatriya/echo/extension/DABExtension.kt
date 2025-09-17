package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.extension.utils.ApiConstants
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

@Suppress("unused", "UNUSED_PARAMETER")
class DABExtension : ExtensionClient, LoginClient.CustomInput, LibraryFeedClient, PlaylistClient,
    TrackClient, SearchFeedClient, AlbumClient, ArtistClient, LyricsClient, LikeClient, PlaylistEditClient {

    companion object {
        private const val PREVIEW_LIMIT = 20
        private const val FULL_LIMIT = 200
        private val STANDARD_LAYOUT_EXTRAS = mapOf(
            "preferred_layout" to "grid_if_wide",
            "preferred_padding" to "16",
            "preferred_item_spacing" to "8"
        )
    }

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
                // If candidate already looks like a direct URL (and not the API /stream endpoint) use it directly
                if (candidate.startsWith("http", ignoreCase = true) && !candidate.contains("/api/stream")) {
                    return@withContext candidate
                }
                // Try resolving via API
                val resolved = api.getStreamUrl(candidate)
                if (!resolved.isNullOrBlank()) return@withContext resolved
                // Fallback construction
                if (candidate.startsWith("http", ignoreCase = true)) candidate
                else ApiConstants.streamUrl(candidate)
            } catch (_: Throwable) {
                if (candidate.startsWith("http", ignoreCase = true)) candidate
                else ApiConstants.streamUrl(candidate)
            }
        }

        return streamUrl.toServerMedia()
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? {
        return try {
            val dabAlbum = withContext(Dispatchers.IO) {
                val albumId = track.album?.id
                if (!albumId.isNullOrBlank()) runCatching { api.getAlbum(albumId) }.getOrNull() else null
            }
            val albumModel: Album? = dabAlbum?.let { converter.toAlbum(it) } ?: track.album
            val albumTracks: List<Track> = dabAlbum?.tracks
                ?.mapNotNull { runCatching { converter.toTrack(it) }.getOrNull() }
                ?.let { applyTrackLayoutExtras(it) }
                ?: emptyList()

            // Merge track artists + album artists, keep order: track.artists first, then album unique
            val mergedArtists = buildList {
                track.artists.forEach { if (it.name.isNotBlank()) add(it) }
                albumModel?.artists?.forEach { albArt ->
                    if (albArt.name.isNotBlank() && track.artists.none { it.id == albArt.id || it.name == albArt.name }) add(albArt)
                }
            }

            val shelves = mutableListOf<Shelf>()

            if (albumTracks.isNotEmpty()) {
                shelves.add(
                    Shelf.Lists.Tracks(
                        id = "track_info_album_tracks_${albumModel?.id ?: track.id}",
                        title = albumModel?.let { "Tracks on ${it.title}" } ?: "Tracks",
                        list = albumTracks,
                        type = Shelf.Lists.Type.Linear
                    )
                )
            }

            if (shelves.isEmpty()) null else shelves.toFeed()
        } catch (_: Throwable) {
            null
        }
    }

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
        requireAuth(); return playlist
    }

    /** Load all tracks for a playlist */
    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        requireAuth()
        return try {
            val tracks: PagedData<Track> = api.getPlaylistTracks(playlist, 1000)
            val trackList = tracks.loadAll()
            if (trackList.isEmpty()) {
                val direct = try { withContext(Dispatchers.IO) { api.fetchAllPlaylistTracks(playlist, 1000) } } catch (_: Throwable) { emptyList() }
                direct.toFeed()
            } else tracks.toFeed()
        } catch (_: Throwable) { emptyList<Track>().toFeed() }
    }

    /** Load playlist feed with tracks shelf */
    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? {
        requireAuth()
        return try {
            val list = try { withContext(Dispatchers.IO) { api.fetchAllPlaylistTracks(playlist, 1000) } } catch (_: Throwable) { emptyList() }
            if (list.isEmpty()) null else listOf(
                Shelf.Lists.Tracks(
                    id = "playlist_tracks_${playlist.id}",
                    title = "Tracks",
                    list = list
                )
            ).toFeed()
        } catch (_: Throwable) { null }
    }

    // --- AlbumClient ---
    override suspend fun loadAlbum(album: Album): Album {
        val dab = withContext(Dispatchers.IO) { api.getAlbum(album.id) }
        return dab?.let { converter.toAlbum(it) } ?: album
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? {
        val dab = withContext(Dispatchers.IO) { api.getAlbum(album.id) }
        val tracks = dab?.tracks?.map { converter.toTrack(it) } ?: return null
        return tracks.toFeed()
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? {
        val dab = withContext(Dispatchers.IO) { api.getAlbum(album.id) }
        val tracks = dab?.tracks?.map { converter.toTrack(it) } ?: return null
        return listOf(
            Shelf.Lists.Tracks(
                id = "album_tracks_${album.id}",
                title = "Tracks",
                list = tracks
            )
        ).toFeed()
    }

    // --- ArtistClient ---
    override suspend fun loadArtist(artist: Artist): Artist {
        val dab = withContext(Dispatchers.IO) { api.getArtistDetails(artist.id) }
        return dab?.let { converter.toArtist(it) } ?: artist
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val albums = withContext(Dispatchers.IO) { api.getArtistDiscography(artist.id) }
        if (albums.isEmpty()) return emptyList<Shelf>().toFeed()
        val converted = albums.map { converter.toAlbum(it) }
        return listOf(
            Shelf.Lists.Items(
                id = "artist_albums_${artist.id}",
                title = "Albums",
                list = converted,
                type = Shelf.Lists.Type.Grid
            )
        ).toFeed()
    }

    // Helpers -------------------------------------------------
    private fun applyTrackLayoutExtras(tracks: List<Track>) =
        tracks.map { it.copy(extras = it.extras + STANDARD_LAYOUT_EXTRAS) }
    private fun applyAlbumLayoutExtras(albums: List<Album>) =
        albums.map { it.copy(extras = it.extras + STANDARD_LAYOUT_EXTRAS) }
    private fun applyArtistLayoutExtras(artists: List<Artist>) =
        artists.map { it.copy(extras = it.extras + STANDARD_LAYOUT_EXTRAS) }

    // Search Feed ---------------------------------------------
    /** Load search feed with tabs for different content types */
    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        if (query.isBlank()) return emptyList<Shelf>().toFeed()
        return Feed(searchTabs) { tab ->
            when (tab?.id) {
                "all" -> {
                    val pagedAll: PagedData<Shelf> = PagedData.Single {
                        try {
                            var (tracksFull, albumsFull, artistsFull) = withContext(Dispatchers.IO) { api.searchAll(query, FULL_LIMIT) }
                            if (albumsFull.isEmpty()) albumsFull = try { withContext(Dispatchers.IO) { api.searchAlbums(query, FULL_LIMIT) } } catch (_: Throwable) { emptyList() }
                            if (artistsFull.isEmpty()) artistsFull = try { withContext(Dispatchers.IO) { api.searchArtists(query, FULL_LIMIT) } } catch (_: Throwable) { emptyList() }

                            val shelves = mutableListOf<Shelf>()
                            if (tracksFull.isNotEmpty()) shelves.add(
                                Shelf.Lists.Items(
                                    id = "search_all_tracks",
                                    title = "Tracks",
                                    list = tracksFull.take(PREVIEW_LIMIT),
                                    type = Shelf.Lists.Type.Linear,
                                    more = if (tracksFull.size >= PREVIEW_LIMIT) buildTrackMoreFeed(tracksFull) else null
                                )
                            )
                            if (albumsFull.isNotEmpty()) shelves.add(
                                Shelf.Lists.Items(
                                    id = "search_all_albums",
                                    title = "Albums",
                                    list = albumsFull.take(PREVIEW_LIMIT),
                                    type = Shelf.Lists.Type.Linear,
                                    more = if (albumsFull.size >= PREVIEW_LIMIT) buildAlbumMoreFeed(albumsFull) else null
                                )
                            )
                            if (artistsFull.isNotEmpty()) shelves.add(
                                Shelf.Lists.Items(
                                    id = "search_all_artists",
                                    title = "Artists",
                                    list = artistsFull.take(PREVIEW_LIMIT),
                                    type = Shelf.Lists.Type.Linear,
                                    more = if (artistsFull.size >= PREVIEW_LIMIT) buildArtistMoreFeed(artistsFull) else null
                                )
                            )
                            shelves
                        } catch (_: Throwable) { emptyList() }
                    }
                    Feed.Data(pagedAll)
                }
                "tracks" -> {
                    val fullTracks = try { withContext(Dispatchers.IO) { api.searchTracks(query, FULL_LIMIT) } } catch (_: Throwable) { emptyList() }
                    if (fullTracks.isEmpty()) Feed.Data(PagedData.empty()) else {
                        val modified = applyTrackLayoutExtras(fullTracks)
                        val preview = Shelf.Lists.Items(
                            id = "search_tracks_preview",
                            title = "Results",
                            list = modified.take(PREVIEW_LIMIT),
                            type = Shelf.Lists.Type.Linear,
                            more = if (modified.size >= PREVIEW_LIMIT) buildTrackMoreFeed(modified) else null
                        )
                        Feed.Data(PagedData.Single { listOf(preview) })
                    }
                }
                "albums" -> {
                    val fullAlbums = try { withContext(Dispatchers.IO) { api.searchAlbums(query, FULL_LIMIT) } } catch (_: Throwable) { emptyList() }
                    if (fullAlbums.isEmpty()) Feed.Data(PagedData.empty()) else {
                        val modified = applyAlbumLayoutExtras(fullAlbums)
                        val preview = Shelf.Lists.Items(
                            id = "search_albums_preview",
                            title = "Albums",
                            list = modified.take(PREVIEW_LIMIT),
                            type = Shelf.Lists.Type.Linear,
                            more = if (modified.size >= PREVIEW_LIMIT) buildAlbumMoreFeed(modified) else null
                        )
                        Feed.Data(PagedData.Single { listOf(preview) })
                    }
                }
                "artists" -> {
                    val fullArtists = try { withContext(Dispatchers.IO) { api.searchArtists(query, FULL_LIMIT) } } catch (_: Throwable) { emptyList() }
                    if (fullArtists.isEmpty()) Feed.Data(PagedData.empty()) else {
                        val modified = applyArtistLayoutExtras(fullArtists)
                        val preview = Shelf.Lists.Items(
                            id = "search_artists_preview",
                            title = "Artists",
                            list = modified.take(PREVIEW_LIMIT),
                            type = Shelf.Lists.Type.Grid,
                            more = if (modified.size >= PREVIEW_LIMIT) buildArtistMoreFeed(modified) else null
                        )
                        Feed.Data(PagedData.Single { listOf(preview) })
                    }
                }
                else -> emptyList<Shelf>().toFeedData()
            }
        }
    }

    // Library Feed -------------------------------------------
    /** Load user's library feed with tabs for different content types */
    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        // If not logged in, raise login required so host can prompt login (no shelves rendered)
        val loggedIn = try { getCurrentUser() != null && api.hasValidSession() } catch (_: Throwable) { false }
        if (!loggedIn) throw ClientException.LoginRequired()

        val genAtStart = sessionGeneration
        return Feed(libraryTabs) { tab ->
            // Strong auth guard each invocation
            if (!api.hasValidSession() || getCurrentUser() == null) throw ClientException.LoginRequired()
            if (genAtStart != sessionGeneration) if (!api.hasValidSession() || getCurrentUser() == null) throw ClientException.LoginRequired()
            when (tab?.id) {
                "all" -> {
                    val playlists = try { loadPlaylists(genAtStart) } catch (e: ClientException.LoginRequired) { throw e } catch (_: Throwable) { null }
                    val favorites = try { withContext(Dispatchers.IO) { api.getFavoritesAuthenticated() } } catch (_: Throwable) { emptyList() }
                    val shelves = buildList {
                        playlists?.takeIf { it.isNotEmpty() }?.let { pls ->
                            add(
                                Shelf.Lists.Items(
                                    id = "library_playlists_all",
                                    title = "Playlists",
                                    list = pls.take(PREVIEW_LIMIT),
                                    type = Shelf.Lists.Type.Linear,
                                    more = if (pls.size >= PREVIEW_LIMIT) buildPlaylistMoreFeed(pls, emptyList()) else null
                                )
                            )
                        }
                        if (favorites.isNotEmpty()) add(
                            Shelf.Lists.Tracks(
                                id = "favorites_tracks_all",
                                title = "Favorites",
                                list = favorites.take(PREVIEW_LIMIT),
                                type = Shelf.Lists.Type.Linear,
                                more = if (favorites.size >= PREVIEW_LIMIT) buildFavoritesMoreFeed(favorites) else null
                            )
                        )
                    }
                    shelvesToFeedData(shelves)
                }
                "playlists" -> {
                    val playlists = try { loadPlaylists(genAtStart) } catch (e: ClientException.LoginRequired) { throw e } catch (_: Throwable) { null }
                    val shelfList = playlists?.takeIf { it.isNotEmpty() }?.let {
                        listOf(
                            Shelf.Lists.Items(
                                id = "library_playlists_tab",
                                title = "Playlists",
                                list = it,
                                type = Shelf.Lists.Type.Linear
                            )
                        )
                    } ?: emptyList()
                    shelvesToFeedData(shelfList)
                }
                "tracks" -> {
                    val favs = try { withContext(Dispatchers.IO) { api.getFavoritesAuthenticated() } } catch (_: Throwable) { emptyList() }
                    if (favs.isEmpty()) shelvesToFeedData(emptyList()) else {
                        val modifiedTracks = applyTrackLayoutExtras(favs)
                        val paged: PagedData<Shelf> = PagedData.Single { modifiedTracks.map { Shelf.Item(it) } }
                        Feed.Data(
                            pagedData = paged,
                            buttons = Feed.Buttons(
                                showSearch = true,
                                showSort = true,
                                showPlayAndShuffle = true,
                                customTrackList = modifiedTracks
                            )
                        )
                    }
                }
                else -> shelvesToFeedData(emptyList())
            }
        }
    }

    // Auth / User --------------------------------------------
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
        currentUser = user; bumpSessionGeneration(); return listOf(user)
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
        // Clear stale cached user if session expired/invalid
        if (currentUser != null && !api.hasValidSession()) currentUser = null
        if (currentUser != null && api.hasValidSession()) return currentUser
        val sessionCookie = settings.getString("session_cookie")
        if (!sessionCookie.isNullOrBlank()) {
            currentUser = withContext(Dispatchers.IO) {
                runCatching { if (api.hasValidSession()) api.getMe() else null }.getOrNull()
            }
            if (currentUser == null) settings.putString("session_cookie", null)
        }
        return currentUser
    }

    // Added helper to centralize auth requirement
    private suspend fun requireAuth() { if (getCurrentUser() == null || !api.hasValidSession()) throw ClientException.LoginRequired() }

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
                val tracksForCover = try { withContext(Dispatchers.IO) { api.fetchAllPlaylistTracks(p, 5) } } catch (_: Throwable) { emptyList() }
                if (gen != sessionGeneration) return null
                val coverTrack = tracksForCover.firstOrNull { it.cover != null }
                if (coverTrack != null) p.copy(cover = coverTrack.cover) else p
            }
        }
        if (gen != sessionGeneration) return null
        return enhanced
    }

    // --- Helper to build Feed.Data from shelves with proper empty handling
    private fun shelvesToFeedData(shelves: List<Shelf>): Feed.Data<Shelf> =
        if (shelves.isEmpty()) Feed.Data(PagedData.empty(), Feed.Buttons.EMPTY, null) else
            Feed.Data(PagedData.Single { shelves }, null, null)

    // --- LikeClient ---
    /** Add or remove track from favorites */
    override suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean) {
        requireAuth(); val track = item as? Track ?: return
        withContext(Dispatchers.IO) { if (shouldLike) api.addFavorite(track) else api.removeFavorite(track.id) }
    }

    /** Check if track is liked/favorited */
    override suspend fun isItemLiked(item: EchoMediaItem): Boolean {
        requireAuth(); val track = item as? Track ?: return false
        return withContext(Dispatchers.IO) { api.isTrackFavorite(track.id) }
    }

    // --- PlaylistEditClient ---
    override suspend fun listEditablePlaylists(track: Track?): List<Pair<Playlist, Boolean>> {
        if (getCurrentUser() == null || !api.hasValidSession()) throw ClientException.LoginRequired()
        val playlists = withContext(Dispatchers.IO) { api.fetchLibraryPlaylistsPage(1, 200) }
        val trackId = track?.id
        return playlists.filter { it.isEditable }.map { p ->
            if (trackId == null) p to false else {
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
    ) { }

    // More feed helpers ---------------------------------------
    private fun buildTrackMoreFeed(all: List<Track>): Feed<Shelf> = Feed(listOf()) {
        val modified = applyTrackLayoutExtras(all)
        val paged: PagedData<Shelf> = PagedData.Single { modified.map { Shelf.Item(it) } }
        Feed.Data(
            pagedData = paged,
            buttons = Feed.Buttons(
                showSearch = true,
                showSort = true,
                showPlayAndShuffle = true,
                customTrackList = modified
            )
        )
    }
    private fun buildAlbumMoreFeed(all: List<Album>): Feed<Shelf> = Feed(listOf()) {
        val modified = applyAlbumLayoutExtras(all)
        val paged: PagedData<Shelf> = PagedData.Single { modified.map { Shelf.Item(it) } }
        Feed.Data(paged)
    }
    private fun buildArtistMoreFeed(all: List<Artist>): Feed<Shelf> = Feed(listOf()) {
        val modified = applyArtistLayoutExtras(all)
        val paged: PagedData<Shelf> = PagedData.Single { modified.map { Shelf.Item(it) } }
        Feed.Data(paged)
    }
    private fun buildPlaylistMoreFeed(allPlaylists: List<Playlist>, aggregatedTracks: List<Track>): Feed<Shelf> = Feed(listOf()) {
        val paged: PagedData<Shelf> = PagedData.Single {
            listOf(
                Shelf.Lists.Items(
                    id = "library_playlists_all_full",
                    title = "Playlists",
                    list = allPlaylists,
                    type = Shelf.Lists.Type.Linear
                )
            )
        }
        Feed.Data(
            pagedData = paged,
            buttons = Feed.Buttons(
                showSearch = true,
                showSort = true,
                showPlayAndShuffle = aggregatedTracks.isNotEmpty(),
                customTrackList = aggregatedTracks.ifEmpty { null }
            )
        )
    }
    private fun buildFavoritesMoreFeed(allTracks: List<Track>): Feed<Shelf> = buildTrackMoreFeed(allTracks)
}
