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
                    for (attempt in 1..maxAttempts) {
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
            currentUser = withContext(Dispatchers.IO) { runCatching { api.getMe() }.getOrNull() }
            if (currentUser == null) {
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
            settings.putString("session_cookie", null)
            currentUser = null
            try { api.shutdown() } catch (_: Throwable) { }
        }
    }

    override suspend fun getCurrentUser(): User? {
        if (currentUser == null) {
            onInitialize()
        }
        return currentUser
    }

    // --- LibraryFeedClient ---
    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        val storedCookie = settings.getString("session_cookie")
        if (storedCookie.isNullOrBlank()) throw ClientException.LoginRequired()

        val me = try { withContext(Dispatchers.IO) { runCatching { api.getMe() }.getOrNull() } } catch (_: Throwable) { null }
        if (me == null) {
            settings.putString("session_cookie", null)
            throw ClientException.LoginRequired()
        }

        return Feed(tabs) { tab ->
            if (getCurrentUser() == null) throw ClientException.LoginRequired()

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
                        val pls = try { withContext(Dispatchers.IO) { api.fetchLibraryPlaylistsPage(1, 50) } } catch (_: Throwable) { emptyList<Playlist>() }
                        if (pls.isEmpty()) emptyList() else pls.map { it.toShelf() }
                    }
                    Feed.Data(paged)
                }
                "albums" -> {
                    val paged = PagedData.Single<Shelf> {
                        loadAlbumShelves() ?: emptyList()
                    }
                    Feed.Data(paged)
                }
                "artists" -> {
                    val paged = PagedData.Single<Shelf> {
                        loadArtistShelves() ?: emptyList()
                    }
                    Feed.Data(paged)
                }
                "tracks" -> {
                    val paged = PagedData.Single<Shelf> {
                        loadFavoritesShelves() ?: emptyList()
                    }
                    Feed.Data(paged, Feed.Buttons(showSearch = false, showSort = false, showPlayAndShuffle = true))
                }
                else -> emptyList<Shelf>().toFeedData()
            }
        }
    }

    private suspend fun loadPlaylistShelves(): List<Shelf>? {
        val pls = try { withContext(Dispatchers.IO) { api.fetchLibraryPlaylistsPage(1, 50) } } catch (_: Throwable) { emptyList<Playlist>() }
        if (pls.isEmpty()) return null

        return pls.map { p ->
            val playlistWithCover = try {
                val existingCover = p.cover
                val derivedCover = if (existingCover == null) {
                    val tracksForCover = try { withContext(Dispatchers.IO) { api.fetchAllPlaylistTracks(p, 6) } } catch (_: Throwable) { emptyList<Track>() }
                    tracksForCover.firstOrNull()?.cover
                } else existingCover

                val finalCover = derivedCover ?: try { "file:///android_asset/ext/icon.png".toResourceUriImageHolder() } catch (_: Throwable) { null }
                if (finalCover != null) p.copy(cover = finalCover) else p
            } catch (_: Throwable) { p }

            Shelf.Item(playlistWithCover)
        }
    }

    private suspend fun loadAlbumShelves(): List<Shelf>? {
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
        val candidates = listOf(
            "https://dab.yeet.su/api/artists?page=1&limit=50",
            "https://dab.yeet.su/api/library/artists?page=1&limit=50"
        )
        val root = probeEndpoints(candidates) ?: return null
        val artists = parseArtistsFromJson(root)
        return if (artists.isEmpty()) null else listOf(Shelf.Lists.Items(id = "my_artists", title = "Artists", list = artists, type = Shelf.Lists.Type.Grid))
    }

    private suspend fun loadFavoritesShelves(): List<Shelf>? {
        val favs = try { withContext(Dispatchers.IO) { api.getFavoritesAuthenticated() } } catch (_: Throwable) { emptyList<Track>() }
        val finalFavs = if (favs.isEmpty()) try { withContext(Dispatchers.IO) { api.getFavorites() } } catch (_: Throwable) { emptyList<Track>() } else favs
        return if (finalFavs.isEmpty()) null else listOf(Shelf.Lists.Tracks(id = "favorites_tracks", title = "Favorites", list = finalFavs))
    }

    private suspend fun probeEndpoints(candidates: List<String>): JsonElement? {
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
                    if (!resp.isSuccessful) continue
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
        val tracks: PagedData<Track> = api.getPlaylistTracks(playlist, 1, 200)
        return tracks.toFeed()
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
        val candidate = listOf("url", "stream_url", "dab_id", "trackId", "track_id", "id", "track")
            .mapNotNull { streamable.extras[it] }
            .firstOrNull()
            ?.toString()
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

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> = emptyList<Shelf>().toFeed()

    // --- AlbumClient ---
    override suspend fun loadAlbum(album: Album): Album {
        val dab = try { withContext(Dispatchers.IO) { api.getAlbum(album.id) } } catch (_: Throwable) { null }
        return dab?.let { converter.toAlbum(it) } ?: album
    }

    override suspend fun loadTracks(album: Album): Feed<Track> {
        val dab = try { withContext(Dispatchers.IO) { api.getAlbum(album.id) } } catch (_: Throwable) { null }
        val tracks = dab?.tracks?.map { converter.toTrack(it) } ?: emptyList()
        return tracks.toFeed()
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? {
        val dabAlbum = try { withContext(Dispatchers.IO) { api.getAlbum(album.id) } } catch (_: Throwable) { null } ?: return null
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
    override suspend fun loadArtist(artist: Artist): Artist {
        val artistDetails = api.getArtistDetails(artist.id)
        return artistDetails?.let { converter.toArtist(it) } ?: artist
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val shelves = mutableListOf<Shelf>()
        val discography = api.getArtistDiscography(artist.id)

        // Add top tracks from first album
        val firstAlbum = discography.firstOrNull()
        if (firstAlbum != null) {
            val albumWithTracks = try { withContext(Dispatchers.IO) { api.getAlbum(firstAlbum.id) } } catch (_: Throwable) { null }
            val topTracks = albumWithTracks?.tracks?.map { converter.toTrack(it) }?.take(10) ?: emptyList()
            if (topTracks.isNotEmpty()) {
                shelves.add(Shelf.Lists.Tracks(
                    id = "artist_top_tracks_${artist.id}",
                    title = "Top Tracks",
                    list = topTracks
                ))
            }
        }

        // Add albums
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
        val lyricsText = try { withContext(Dispatchers.IO) { api.getLyrics(artist, lyrics.title) } } catch (_: Throwable) { null }

        if (lyricsText.isNullOrBlank()) return lyrics.copy(lyrics = dev.brahmkshatriya.echo.common.models.Lyrics.Simple(""))

        val lyricObj = try { converter.toLyricFromText(lyricsText) } catch (_: Throwable) { null }
        if (lyricObj != null) return lyrics.copy(lyrics = lyricObj)

        val fallback = dev.brahmkshatriya.echo.common.models.Lyrics.Simple(converter.cleanPlainText(lyricsText))
        return lyrics.copy(lyrics = fallback)
    }

    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<dev.brahmkshatriya.echo.common.models.Lyrics> {
        val artistName = track.artists.firstOrNull()?.name ?: return emptyList<dev.brahmkshatriya.echo.common.models.Lyrics>().toFeed()
        val lyricsText = try { withContext(Dispatchers.IO) { api.getLyrics(artistName, track.title) } } catch (_: Throwable) { null }

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
