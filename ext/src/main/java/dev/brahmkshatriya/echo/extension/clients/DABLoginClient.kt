package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.extension.DABApi
import dev.brahmkshatriya.echo.extension.DABParser.toUser
import dev.brahmkshatriya.echo.extension.DABSession
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class DABLoginClient(
    private val api: DABApi,
    private val session: DABSession
) : LoginClient {

    override val source: String = "DAB"
    override val canLogout: Boolean = true

    override val loginFields: List<Setting> = listOf(
        Setting.TextInput(
            key = "email",
            title = "Email",
            private = false
        ),
        Setting.TextInput(
            key = "password",
            title = "Password",
            private = true
        )
    )

    // Added the required `suspend` keyword here.
    override suspend fun login(inputs: Map<String, String>): User {
        val email = inputs["email"] ?: ""
        val password = inputs["password"] ?: ""

        val response = api.callApi(
            path = "/auth/login",
            method = "POST"
        ) {
            put("email", email)
            put("password", password)
        }

        val userJson = response["user"]?.jsonObject ?: throw Exception("Login failed: User data not found")
        val user = userJson.toUser()

        session.login(user)
        return user
    }

    override suspend fun logout() {
        session.logout()
        // This call is optional but recommended to clear the session on the server-side.
        try {
            api.callApi(path = "/auth/logout", method = "POST")
        } catch (e: Exception) {
            // Ignore errors here as the local session is already cleared.
        }
    }

    override suspend fun getLoggedInUser(): User? {
        return session.getLoggedInUser()
    }
}