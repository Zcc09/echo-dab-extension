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
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import java.net.URLEncoder
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import android.util.Log
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
    private val api: DABApi by lazy { DABApi(client, converter, settings).also { converter.api = it } }
    private var currentUser: User? = null

    private val tabs = listOf(
        Tab(id = "all", title = "All"),
        Tab(id = "playlists", title = "Playlists"),
        Tab(id = "tracks", title = "Tracks"),
        Tab(id = "albums", title = "Albums"),
        Tab(id = "artists", title = "Artists")
    )

    private val json by lazy { Json { ignoreUnknownKeys = true; coerceInputValues = true } }

    // --- LikeClient ---
    override suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean) {
        if (getCurrentUser() == null) throw ClientException.LoginRequired()
        if (item !is Track) throw ClientException.NotSupported("likeItem")

        val trackId = item.extras["dab_id"] ?: item.extras["id"] ?: item.id

        val success = withContext(Dispatchers.IO) {
            try {
                if (shouldLike) {
                    api.addFavorite(item)
                } else {
                    val maxAttempts = 3
                    var lastResult = false
                    for (attemptNum in 1..maxAttempts) {
                        try {
                            api.removeFavorite(trackId)
                            delay(300L)
                            val stillFav = try { api.isTrackFavorite(trackId) } catch (_: Throwable) { true }
                            if (!stillFav) {
                                lastResult = true
                                break
                            }
                            lastResult = false
                        } catch (_: Throwable) {
                            lastResult = false
                        }
                        delay(200L)
                    }
                    lastResult
                }
            } catch (_: Throwable) {
                false
            }
        }

        if (!success) {
            throw IllegalStateException("Failed to ${if (shouldLike) "add" else "remove"} favorite for trackId=$trackId")
        }
    }

    override suspend fun isItemLiked(item: EchoMediaItem): Boolean {
        if (getCurrentUser() == null) throw ClientException.LoginRequired()
        if (item !is Track) throw ClientException.NotSupported("isItemLiked")
        val trackId = item.extras["dab_id"] ?: item.extras["id"] ?: item.id
        return try {
            withContext(Dispatchers.IO) { api.isTrackFavorite(trackId) }
        } catch (_: Throwable) {
            false
        }
    }

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
            try { api.shutdown() } catch (_: Throwable) { }
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

    // --- LibraryFeedClient ---
    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        // Check authentication first
        val user = getCurrentUser()
        if (user == null || !api.hasValidSession()) {
            throw ClientException.LoginRequired()
        }

        return Feed(tabs) { tab ->
            // Double-check authentication for each tab load
            if (getCurrentUser() == null || !api.hasValidSession()) {
                throw ClientException.LoginRequired()
            }

            when (tab?.id) {
                "all" -> {
                    val pagedAll: PagedData<Shelf> = PagedData.Single {
                        supervisorScope {
                            val deferreds = tabs.map { t ->
                                async(Dispatchers.Default) {
                                    when (t.id) {
                                        "playlists" -> loadPlaylistShelves()
                                        "albums" -> loadAlbumShelves()
                                        "artists" -> loadArtistShelves()
                                        "tracks" -> loadFavoritesShelves()
                                        else -> null
                                    }
                                }
                            }
                            deferreds.mapNotNull { it.await() }.flatten()
                        }
                    }
                    Feed.Data(pagedAll)
                }
                "playlists" -> {
                    val paged = PagedData.Single<Shelf> {
                        if (!api.hasValidSession()) throw ClientException.LoginRequired()
                        val pls = try { withContext(Dispatchers.IO) { api.fetchLibraryPlaylistsPage(1, 50) } } catch (_: Throwable) { emptyList<Playlist>() }
                        if (pls.isEmpty()) emptyList() else pls.map { it.toShelf() }
                    }
                    Feed.Data(paged)
                }
                "albums" -> {
                    val paged = PagedData.Single<Shelf> {
                        if (!api.hasValidSession()) throw ClientException.LoginRequired()
                        loadAlbumShelves() ?: emptyList()
                    }
                    Feed.Data(paged)
                }
                "artists" -> {
                    val paged = PagedData.Single<Shelf> {
                        if (!api.hasValidSession()) throw ClientException.LoginRequired()
                        loadArtistShelves() ?: emptyList()
                    }
                    Feed.Data(paged)
                }
                "tracks" -> {
                    val paged = PagedData.Single<Shelf> {
                        if (!api.hasValidSession()) throw ClientException.LoginRequired()
                        loadFavoritesShelves() ?: emptyList()
                    }
                    Feed.Data(paged, Feed.Buttons(showSearch = false, showSort = false, showPlayAndShuffle = true))
                }
                else -> emptyList<Shelf>().toFeedData()
            }
        }
    }

    private suspend fun loadPlaylistShelves(): List<Shelf>? {
        if (!api.hasValidSession()) return null
        val pls = try { withContext(Dispatchers.IO) { api.fetchLibraryPlaylistsPage(1, 50) } } catch (_: Throwable) { emptyList<Playlist>() }
        if (pls.isEmpty()) return null

        return pls.map { playlist ->
            val playlistWithCover = try {
                // If playlist already has a cover, use it
                if (playlist.cover != null) {
                    Log.d("DABExtension", "Playlist ${playlist.id} already has cover, using existing")
                    playlist
                } else {
                    // Fetch tracks to get cover from latest added track
                    Log.d("DABExtension", "Playlist ${playlist.id} has no cover, fetching tracks for cover")
                    val tracksForCover = try {
                        withContext(Dispatchers.IO) {
                            // Get first few tracks to find a cover
                            api.fetchAllPlaylistTracks(playlist, 10)
                        }
                    } catch (e: Throwable) {
                        Log.w("DABExtension", "Failed to fetch tracks for cover: ${e.message}")
                        emptyList<Track>()
                    }

                    if (tracksForCover.isNotEmpty()) {
                        // Try to find the first track with a cover
                        val trackWithCover = tracksForCover.firstOrNull { it.cover != null }
                        if (trackWithCover != null) {
                            Log.d("DABExtension", "Using cover from track '${trackWithCover.title}' for playlist ${playlist.id}")
                            playlist.copy(cover = trackWithCover.cover)
                        } else {
                            Log.d("DABExtension", "No tracks with covers found for playlist ${playlist.id}, using default")
                            // Use a default cover as fallback
                            try {
                                playlist.copy(cover = "file:///android_asset/ext/icon.png".toResourceUriImageHolder())
                            } catch (_: Throwable) {
                                playlist
                            }
                        }
                    } else {
                        Log.d("DABExtension", "No tracks found for playlist ${playlist.id}, using default cover")
                        // Use default cover when no tracks are found
                        try {
                            playlist.copy(cover = "file:///android_asset/ext/icon.png".toResourceUriImageHolder())
                        } catch (_: Throwable) {
                            playlist
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w("DABExtension", "Exception while setting playlist cover: ${e.message}")
                playlist
            }

            Shelf.Item(playlistWithCover)
        }
    }

    private suspend fun loadAlbumShelves(): List<Shelf>? {
        if (!api.hasValidSession()) return null
        val candidates = listOf(
            "https://dab.yeet.su/api/albums?page=1&limit=50",
            "https://dab.yeet.su/api/library/albums?page=1&limit=50",
            "https://dab.yeet.su/api/collections/albums?page=1&limit=50"
        )
        val root = probeEndpoints(candidates) ?: return null
        val albums = parseAlbumsFromJson(root)
        return if (albums.isEmpty()) null else listOf(Shelf.Lists.Items(id = "my_albums", title = "Albums", list = albums, type = Shelf.Lists.Type.Grid))
    }

    private suspend fun loadArtistShelves(): List<Shelf>? {
        if (!api.hasValidSession()) return null
        val candidates = listOf(
            "https://dab.yeet.su/api/artists?page=1&limit=50",
            "https://dab.yeet.su/api/library/artists?page=1&limit=50"
        )
        val root = probeEndpoints(candidates) ?: return null
        val artists = parseArtistsFromJson(root)
        return if (artists.isEmpty()) null else listOf(Shelf.Lists.Items(id = "my_artists", title = "Artists", list = artists, type = Shelf.Lists.Type.Grid))
    }

    private suspend fun loadFavoritesShelves(): List<Shelf>? {
        if (!api.hasValidSession()) return null
        val favs = try { withContext(Dispatchers.IO) { api.getFavoritesAuthenticated() } } catch (_: Throwable) { emptyList<Track>() }
        return if (favs.isEmpty()) null else listOf(Shelf.Lists.Tracks(id = "favorites_tracks", title = "Favorites", list = favs))
    }

    private fun probeEndpoints(candidates: List<String>): JsonElement? {
        if (!api.hasValidSession()) return null

        for (url in candidates) {
            try {
                val rb = okhttp3.Request.Builder().url(url)
                val cookie = settings.getString("session_cookie")?.let { raw ->
                    val firstPart = raw.split(';').firstOrNull()?.trim() ?: return@let null
                    if (firstPart.isEmpty()) return@let null
                    if (firstPart.contains('=')) firstPart else "session=$firstPart"
                }
                if (!cookie.isNullOrBlank()) rb.header("Cookie", cookie)
                rb.header("Accept", "application/json").header("User-Agent", "EchoDAB-Extension/1.0")

                client.newCall(rb.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        // Clear invalid session on auth errors
                        if (resp.code == 401 || resp.code == 403) {
                            settings.putString("session_cookie", null)
                            currentUser = null
                            api.clearSession()
                        }
                        continue
                    }
                    val body = resp.body?.string() ?: continue
                    try {
                        return json.decodeFromString<JsonElement>(body)
                    } catch (_: Throwable) { }
                }
            } catch (_: Throwable) { }
        }
        return null
    }

    private fun parseAlbumsFromJson(root: JsonElement): List<Album> {
        val arr = when (root) {
            is JsonObject -> (root["albums"] as? JsonArray) ?: (root["data"] as? JsonArray) ?: (root["items"] as? JsonArray)
            is JsonArray -> root
            else -> null
        } ?: return emptyList()

        return arr.mapNotNull { el ->
            if (el is JsonObject) {
                try {
                    val dabAlbum = json.decodeFromJsonElement(dev.brahmkshatriya.echo.extension.models.DabAlbum.serializer(), el)
                    converter.toAlbum(dabAlbum)
                } catch (_: Throwable) {
                    try {
                        val id = (el["id"] as? JsonPrimitive)?.content ?: (el["albumId"] as? JsonPrimitive)?.content ?: (el["slug"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                        val title = (el["title"] as? JsonPrimitive)?.content ?: (el["name"] as? JsonPrimitive)?.content ?: "Album"
                        val artist = (el["artist"] as? JsonPrimitive)?.content ?: ""
                        Album(id = id, title = title, cover = null, artists = listOf(Artist(id = "", name = artist)), trackCount = (el["trackCount"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L)
                    } catch (_: Throwable) { null }
                }
            } else null
        }
    }

    private fun parseArtistsFromJson(root: JsonElement): List<Artist> {
        val arr = when (root) {
            is JsonObject -> (root["artists"] as? JsonArray) ?: (root["data"] as? JsonArray) ?: (root["items"] as? JsonArray)
            is JsonArray -> root
            else -> null
        } ?: return emptyList()

        return arr.mapNotNull { el ->
            if (el is JsonObject) {
                try {
                    val dabArtist = json.decodeFromJsonElement(dev.brahmkshatriya.echo.extension.models.DabArtist.serializer(), el)
                    converter.toArtist(dabArtist)
                } catch (_: Throwable) {
                    try {
                        val id = (el["id"] as? JsonPrimitive)?.content ?: (el["artistId"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                        val name = (el["name"] as? JsonPrimitive)?.content ?: (el["title"] as? JsonPrimitive)?.content ?: "Artist"
                        Artist(id = id, name = name, cover = null)
                    } catch (_: Throwable) { null }
                }
            } else null
        }
    }

    // --- PlaylistClient ---
    override suspend fun loadPlaylist(playlist: Playlist): Playlist = playlist

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        Log.d("DABExtension", "loadTracks called for playlist=${playlist.id}, title='${playlist.title}'")

        try {
            val tracks: PagedData<Track> = api.getPlaylistTracks(playlist, 1, 200)
            val trackList = tracks.loadAll()
            Log.d("DABExtension", "loadTracks returning ${trackList.size} tracks for playlist=${playlist.id}")

            if (trackList.isEmpty()) {
                Log.w("DABExtension", "No tracks found for playlist=${playlist.id}, attempting direct fetch")
                // Try direct fetch as fallback
                val directTracks = try {
                    withContext(Dispatchers.IO) {
                        api.fetchAllPlaylistTracks(playlist, 1000)
                    }
                } catch (e: Throwable) {
                    Log.e("DABExtension", "Direct fetch failed for playlist=${playlist.id}: ${e.message}")
                    emptyList()
                }
                Log.d("DABExtension", "Direct fetch returned ${directTracks.size} tracks for playlist=${playlist.id}")
                return directTracks.toFeed()
            }

            return tracks.toFeed()
        } catch (e: Throwable) {
            Log.e("DABExtension", "Exception in loadTracks for playlist=${playlist.id}: ${e.message}", e)
            return emptyList<Track>().toFeed()
        }
    }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? {
        try {
            val tracksList = try {
                withContext(Dispatchers.IO) {
                    api.fetchAllPlaylistTracks(playlist, 1000)
                }
            } catch (_: Throwable) { emptyList<Track>() }

            val shelves = mutableListOf<Shelf>()
            if (tracksList.isNotEmpty()) {
                shelves.add(Shelf.Lists.Tracks(
                    id = "playlist_tracks_${playlist.id}",
                    title = "Tracks",
                    list = tracksList
                ))
            }

            return if (shelves.isNotEmpty()) shelves.toFeed() else null
        } catch (e: Throwable) {
            Log.w("DABExtension", "loadFeed(playlist) failed for id=${playlist.id}", e)
            return null
        }
    }

    // --- TrackClient ---
    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track = track

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
        val candidate = streamable.extras.values.firstNotNullOfOrNull { it }
            ?: error("Track ID not found in extras")

        val streamUrl = withContext(Dispatchers.IO) {
            try {
                // Check cache first
                val cachedUrl = api.getCachedStreamUrl(candidate)
                if (!cachedUrl.isNullOrBlank()) return@withContext cachedUrl

                // If it's already a direct URL, return it
                if (candidate.startsWith("http", ignoreCase = true) && !candidate.contains("/api/stream")) {
                    return@withContext candidate
                }

                // Try stream resolution
                val resolved = api.getStreamUrl(candidate)
                if (!resolved.isNullOrBlank()) return@withContext resolved

                // Fallback
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

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        if (query.isBlank()) {
            Log.d("DABExtension", "Empty search query, returning empty feed")
            return emptyList<Shelf>().toFeed()
        }

        Log.d("DABExtension", "loadSearchFeed called with query: '$query'")

        // Simple tracks-only search - no tabs needed for single content type
        val paged = PagedData.Single<Shelf> {
            try {
                Log.d("DABExtension", "Searching for tracks (no authentication required)")
                val tracks = withContext(Dispatchers.IO) {
                    api.searchTracks(query, 50)
                }
                Log.d("DABExtension", "Found ${tracks.size} track results")

                if (tracks.isEmpty()) {
                    emptyList()
                } else {
                    listOf(Shelf.Lists.Tracks(
                        id = "search_tracks",
                        title = "Results",
                        list = tracks
                    ))
                }
            } catch (e: Throwable) {
                Log.e("DABExtension", "Exception in track search: ${e.message}", e)
                emptyList()
            }
        }

        return paged.toFeed()
    }

    // --- AlbumClient ---
    override suspend fun loadAlbum(album: Album): Album {
        val dabAlbum = withContext(Dispatchers.IO) {
            api.getAlbum(album.id)
        }
        return dabAlbum?.let { converter.toAlbum(it) } ?: album
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? {
        val dabAlbum = withContext(Dispatchers.IO) {
            api.getAlbum(album.id)
        }
        return dabAlbum?.tracks?.map { converter.toTrack(it) }?.toFeed()
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? {
        val dabAlbum = withContext(Dispatchers.IO) {
            api.getAlbum(album.id)
        }
        val tracks = dabAlbum?.tracks?.map { converter.toTrack(it) } ?: return null
        if (tracks.isEmpty()) return null

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
        return dabArtist?.let { converter.toArtist(it) } ?: artist
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val albums = withContext(Dispatchers.IO) {
            api.getArtistDiscography(artist.id)
        }

        if (albums.isEmpty()) return emptyList<Shelf>().toFeed()

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
}
