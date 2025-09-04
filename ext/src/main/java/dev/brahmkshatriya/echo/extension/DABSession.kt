package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.settings.Settings
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages the user's session state in-memory.
 * This class is platform-agnostic and does NOT handle persistence.
 * The main application is responsible for loading the token from storage and initializing the session.
 */
class DABSession private constructor() {

    /**
     * A simple data class to hold the necessary credentials for the DAB API.
     */
    data class DABCredentials(
        val token: String?
    )

    // In-memory, thread-safe cache for the user's credentials. Null if not logged in.
    private val credentialsRef = AtomicReference<DABCredentials?>()

    // User-configurable settings provided by the main application.
    var settings: Settings? = null

    /**
     * Public accessor for the current user's credentials.
     * Throws an exception if the user is not logged in, as this is an invalid state for most API calls.
     */
    val credentials: DABCredentials
        get() = credentialsRef.get()
            ?: throw IllegalStateException("User is not logged in. The session must be initialized first.")

    /**
     * Checks if the user is currently logged in.
     * @return `true` if credentials are set, `false` otherwise.
     */
    fun isLoggedIn(): Boolean {
        return credentialsRef.get() != null
    }

    /**
     * Initializes the session with a token.
     * This should be called by the main app at startup (with a saved token)
     * or after a successful login.
     *
     * @param token The user's authentication token.
     */
    fun initialize(token: String?) {
        credentialsRef.set(DABCredentials(token = token))
    }

    /**
     * Clears the user's credentials from the in-memory cache.
     * This is typically called on logout.
     */
    fun clear() {
        credentialsRef.set(null)
    }

    companion object {
        // The singleton instance of the session.
        @Volatile
        private var instance: DABSession? = null

        /**
         * Gets the singleton instance of the DABSession.
         */
        fun getInstance(): DABSession {
            return instance ?: synchronized(this) {
                instance ?: DABSession().also { instance = it }
            }
        }
    }
}
