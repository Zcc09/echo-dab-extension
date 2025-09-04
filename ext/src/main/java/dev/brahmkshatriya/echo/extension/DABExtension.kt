package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.extension.clients.DABAlbumClient
import dev.brahmkshatriya.echo.extension.clients.DABArtistClient
import dev.brahmkshatriya.echo.extension.clients.DABHomefeedClient
import dev.brahmkshatriya.echo.extension.clients.DABLibraryClient
import dev.brahmkshatriya.echo.extension.clients.DABLoginClient
import dev.brahmkshatriya.echo.extension.clients.DABLyricsClient
import dev.brahmkshatriya.echo.extension.clients.DABPlaylistClient
import dev.brahmkshatriya.echo.extension.clients.DABSearchClient
import dev.brahmkshatriya.echo.extension.clients.DABTrackClient
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

class DABExtension : Extension() {

    override val name = "DAB"

    override val settings: List<Setting> = listOf(
        Setting.TextInput(
            key = "lastfm_api_key",
            title = "Last.fm API Key",
            subtitle = "Required for the Home feed",
            private = true
        )
    )

    override val clients by lazy {
        // Centralize all initializations here for clarity.
        val okHttpClient by lazy {
            val cookieJar = object : CookieJar {
                private val cookieStore = mutableMapOf<String, List<Cookie>>()
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    cookieStore[url.host] = cookies
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    return cookieStore[url.host] ?: emptyList()
                }
            }
            OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .build()
        }

        val session = DABSession(settings)
        val api = DABApi(session, okHttpClient)
        val lastFmApi = LastFmApi(settings.get<String>("lastfm_api_key") ?: "")

        listOf(
            DABLoginClient(api, session),
            DABHomefeedClient(lastFmApi),
            DABSearchClient(api),
            DABLibraryClient(api),
            DABAlbumClient(api),
            DABArtistClient(api),
            DABPlaylistClient(api),
            DABTrackClient(api),
            DABLyricsClient(api)
        )
    }
}