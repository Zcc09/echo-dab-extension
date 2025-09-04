package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.LoginClient

/**
 * A base exception class for handled client exceptions,
 * the extension can throw other exceptions too, but they will be handled by the App.
 *
 * The app handles the following exceptions:
 * - [LoginRequired] - When the user is not logged in
 * - [Unauthorized] - When the user is not authorized, will log out the user.
 * - [NotSupported] - When the extension does not support an operation.
 * - [InvalidLogin] - When login credentials are incorrect or invalid.
 * - [LoginFailed] - A more general login failure, potentially with a specific message.
 *
 * @see [LoginClient]
 */
sealed class ClientException(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * To be thrown when some operation requires the user to be logged in.
     */
    open class LoginRequired(message: String = "Login required.", cause: Throwable? = null) : ClientException(message, cause)

    /**
     * To be thrown when the user is not authorized to perform an operation.
     * The user will be logged out from the app.
     *
     * @param userId The id of the user
     */
    class Unauthorized(val userId: String, message: String = "Unauthorized user.", cause: Throwable? = null) : LoginRequired(message, cause)

    /**
     * To be thrown when the extension does not support an operation.
     *
     * @param operation The name of the operation that is not supported
     */
    class NotSupported(val operation: String, message: String = "Operation '$operation' is not supported.", cause: Throwable? = null) : ClientException(message, cause)

    /**
     * To be thrown when the provided login credentials (e.g., token, username/password) are invalid.
     * This indicates a specific failure of the login attempt due to bad input.
     */
    class InvalidLogin(message: String = "Invalid login credentials.", cause: Throwable? = null) : ClientException(message, cause)

    /**
     * To be thrown for a general login failure that isn't due to invalid credentials,
     * e.g., network issues during login validation or an unexpected server response.
     */
    class LoginFailed(message: String = "Login failed.", cause: Throwable? = null) : ClientException(message, cause)
}