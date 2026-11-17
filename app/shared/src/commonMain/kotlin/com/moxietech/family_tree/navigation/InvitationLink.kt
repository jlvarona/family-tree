package com.moxietech.family_tree.navigation

import io.ktor.http.parseQueryString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * FT-011: an invitation taken from an acceptance link `#/trees/{treeId}/join?token=…`.
 * The tree and the token both travel in the URL fragment, which the browser never sends
 * to a server; the app sends them to `POST /api/v1/trees/{treeId}/invitations/redeem`
 * with the token in the body. Not a data class, so [toString] never prints the token.
 */
class InvitationLink(val treeId: String, val token: String) {
    override fun toString() = "InvitationLink(treeId=$treeId)"

    /** The form kept in sessionStorage while the invitee signs in (this tab only). */
    fun encode(): String = json.encodeToString(Stored.serializer(), Stored(treeId, token))

    @Serializable
    private class Stored(val treeId: String, val token: String)

    companion object {
        /** The server's token format: 32 random bytes, base64url without padding. */
        private val TOKEN = Regex("[A-Za-z0-9_-]{43}")
        private val json = Json { ignoreUnknownKeys = true }

        /** True for a join route that carries query parameters: a link whose secret must leave the address bar. */
        fun isLink(fragment: String): Boolean {
            val text = fragment.removePrefix("#")
            return Route.parse(text) is Route.JoinTree && '?' in text
        }

        /** The invitation in a join link's fragment, or null when it isn't one or its token is malformed. */
        fun parse(fragment: String): InvitationLink? {
            val text = fragment.removePrefix("#")
            val route = Route.parse(text) as? Route.JoinTree ?: return null
            if ('?' !in text) return null
            val token = try {
                parseQueryString(text.substringAfter('?')).getAll("token")?.singleOrNull()
            } catch (e: Exception) {
                null
            } ?: return null
            return if (TOKEN.matches(token)) InvitationLink(route.treeId, token) else null
        }

        fun decode(stored: String): InvitationLink? = try {
            json.decodeFromString(Stored.serializer(), stored).let { InvitationLink(it.treeId, it.token) }
                .takeIf { TOKEN.matches(it.token) }
        } catch (e: Exception) {
            null
        }
    }
}
