package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.extension.DABApi
import dev.brahmkshatriya.echo.extension.DABSession
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import dev.brahmkshatriya.echo.extension.DABParser.toUser // Import the parser function

class DABLoginClient(private val api: DABApi, private val session: DABSession) : LoginClient {
    // ... (fields and logout/getLoggedInUser methods remain the same)
    override suspend fun login(inputs: List<String>): User {
        val email = inputs[0]
        val password = inputs[1]
        val response = api.callApi(path = "/auth/login", method = "POST") {
            put("email", email)
            put("password", password)
        }
        val userJson = response["user"]?.jsonObject ?: throw Exception("Login failed")
        // Use the centralized parser
        val user = userJson.toUser()
        session.login(user)
        return user
    }
}

    override suspend fun logout() {
        // Clear the session data on logout.
        session.logout()
        // Optional: Call a /auth/logout endpoint if it exists and handles server-side session clearing.
        // api.callApi(path = "/auth/logout", method = "POST")
    }

    override suspend fun getLoggedInUser(): User? {
        // Return the user if they are already logged in.
        return session.getLoggedInUser()
    }

    // Helper to parse the User JSON into Echo's User model.
    private fun JsonObject.toUser(): User {
        val id = this["id"]?.jsonPrimitive?.content ?: ""
        val username = this["username"]?.jsonPrimitive?.content ?: "Unknown"
        val avatar = null // The API doesn't seem to provide an avatar URL.
        return User(id, username, avatar, "DAB")
    }
}