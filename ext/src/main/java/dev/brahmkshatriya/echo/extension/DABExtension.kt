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

@Suppress("unused")
class DABExtension : ExtensionClient, LoginClient.CustomInput, LibraryFeedClient, PlaylistClient,
    TrackClient, SearchFeedClient, AlbumClient, ArtistClient, LyricsClient, dev.brahmkshatriya.echo.common.clients.LikeClient {

    private lateinit var settings: Settings
    private val client by lazy {
        OkHttpClient.Builder()
            .addInterceptor(RateLimitInterceptor(50))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val converter by lazy { Converter() }
    // Create the API and immediately assign it back to the converter so the converter can fetch images
    private val api: DABApi by lazy { DABApi(client, converter, settings).also { converter.api = it } }
    private var currentUser: User? = null

    // Prefer direct API calls with runCatching wrappers for safety and performance.
    // Suspend helpers that execute blocking API calls on the IO dispatcher
    private suspend fun getAlbumSafe(albumId: String): dev.brahmkshatriya.echo.extension.models.DabAlbum? =
        withContext(Dispatchers.IO) { runCatching { api.getAlbum(albumId) }.getOrNull() }

    private suspend fun getLyricsSafe(artist: String, title: String): String? =
        withContext(Dispatchers.IO) { runCatching { api.getLyrics(artist, title) }.getOrNull() }

    private suspend fun isTrackFavoritedSafe(trackId: String): Boolean =
        withContext(Dispatchers.IO) { runCatching { api.isTrackFavorited(trackId) }.getOrDefault(false) }

    private suspend fun likeTrackSafe(trackId: String, shouldLike: Boolean): Boolean =
        withContext(Dispatchers.IO) { runCatching { api.likeTrack(trackId, shouldLike) }.getOrDefault(false) }

    // From ExtensionClient
    override fun setSettings(settings: Settings) {
        this.settings = settings
        // No diagnostics in production; store settings silently
    }

    override suspend fun getSettingItems(): List<Setting> {
        // No custom favorites endpoint settings — use the documented /favorites API only.
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
        val user = withContext(Dispatchers.IO) { api.loginAndSaveCookie(email, password) }
        currentUser = user
        // Do not block on fetching favorites here; UI will load a quick page when needed
        return listOf(user)
    }

    // From LoginClient
    override fun setLoginUser(user: User?) {
        if (user == null) {
            settings.putString("session_cookie", null)
            currentUser = null
            // No long-lived favorites cache to clear
        }
    }

    // No cached favorites logic: use single-page fetches for fast UI responses.
    override suspend fun getCurrentUser(): User? {
        if (currentUser == null) {
            onInitialize()
        }
        return currentUser
    }

    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        if (getCurrentUser() == null) throw ClientException.LoginRequired()

        // No diagnostics: build the library feed

        // Prepare paged sources:
        // Playlists: materialize first page into a single Shelf.Items
        val playlistsList = withContext(Dispatchers.IO) { api.fetchLibraryPlaylistsPage(1, 50) }
        // playlists list fetched
        val playlistsPaged = PagedData.Single<Shelf> {
            if (playlistsList.isEmpty()) emptyList()
            else listOf(Shelf.Lists.Items(
                id = "my_playlists",
                title = "Playlists",
                list = playlistsList,
                type = Shelf.Lists.Type.Grid
            ))
        }


        val firstFavoritesProbe = withContext(Dispatchers.IO) { runCatching { api.getFavorites() }.getOrDefault(emptyList()) }



        val favoritesHeader = PagedData.Single<Shelf> {
            listOf(Shelf.Category(id = "favorites_header", title = "Favorites"))
        }


        val favoritesItemsForAll: PagedData<Shelf> = PagedData.Single {
            val favs = if (firstFavoritesProbe.isNotEmpty()) firstFavoritesProbe
            else try { withContext(Dispatchers.IO) { api.fetchFavoritesPage(1, 50) } } catch (_: Throwable) { emptyList() }

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
                        val pageList = try { api.fetchFavoritesPage(pageNum, pageSize) } catch (_: Throwable) { emptyList<Track>() }
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
        // Extract the track ID from the stream URL
        val trackId = streamable.extras["url"]?.substringAfter("trackId=") ?: error("Track ID not found in URL")

        val streamUrl = withContext(Dispatchers.IO) { api.getStreamUrl(trackId) }

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
        val tracks = withContext(Dispatchers.IO) { runCatching { api.searchTracks(query, 8) }.getOrElse { emptyList<Track>() } }
        val albums = withContext(Dispatchers.IO) { runCatching { api.searchAlbums(query, 4) }.getOrElse { emptyList() } }
        val artists = withContext(Dispatchers.IO) { runCatching { api.searchArtists(query, 4) }.getOrElse { emptyList() } }

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
                 type = Shelf.Lists.Type.Grid // Grid looks better for albums
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
    // --- LikeClient ---
    override suspend fun isItemLiked(item: dev.brahmkshatriya.echo.common.models.EchoMediaItem): Boolean {
         // Extract track id and fall back to direct API check (no cache)
         val trackId = when (item) {
             is Track -> item.id
             else -> item.extras["id"] ?: item.extras["trackId"] ?: item.extras["url"]?.substringAfter("trackId=")
         } ?: return false

         if (getCurrentUser() == null) return false
         return try { isTrackFavoritedSafe(trackId) } catch (_: Throwable) { false }
     }

     override suspend fun likeItem(item: dev.brahmkshatriya.echo.common.models.EchoMediaItem, shouldLike: Boolean) {
         val trackId = when (item) {
             is Track -> item.id
             else -> item.extras["id"] ?: item.extras["trackId"] ?: item.extras["url"]?.substringAfter("trackId=")
         } ?: return

         if (getCurrentUser() == null) return

         try {
             likeTrackSafe(trackId, shouldLike)
         } catch (_: Throwable) {
             // ignore failures
         }
     }
 }
