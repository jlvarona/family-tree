package com.moxietech.family_tree.server.identity

import com.moxietech.family_tree.api.AccountDto
import com.moxietech.family_tree.api.TreeDto
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals

/** Calls to the production routes used by the FT-003 tests, plus a few direct SQL reads. */
internal object IdentityTestApi {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun HttpClient.me(token: String): AccountDto {
        val response = get("/api/v1/me") { bearerAuth(token) }
        return json.decodeFromString(response.bodyWithStatus(HttpStatusCode.OK))
    }

    suspend fun HttpClient.createTree(token: String, name: String): TreeDto {
        val response = post("/api/v1/trees") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name"}""")
        }
        return json.decodeFromString(response.bodyWithStatus(HttpStatusCode.Created))
    }

    suspend fun HttpClient.treeIds(token: String): List<String> {
        val response = get("/api/v1/trees") { bearerAuth(token) }
        return json.decodeFromString<List<TreeDto>>(response.bodyWithStatus(HttpStatusCode.OK)).map { it.id }
    }

    suspend fun HttpClient.getTree(token: String, treeId: String): HttpResponse =
        get("/api/v1/trees/$treeId") { bearerAuth(token) }

    suspend fun HttpClient.createPlace(token: String, treeId: String, name: String): HttpResponse =
        post("/api/v1/trees/$treeId/places") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name"}""")
        }

    suspend fun HttpResponse.bodyWithStatus(status: HttpStatusCode): String {
        val body = bodyAsText()
        assertEquals(status, this.status, body)
        return body
    }

    fun DataSource.accountsWithIdentity(issuer: String, subject: String): Long =
        query("select count(*) from account where oidc_issuer = ? and oidc_subject = ?", issuer, subject)!!.toLong()

    fun DataSource.query(sql: String, vararg args: Any?): String? = connection.use { it.query(sql, *args) }

    /** First column of the first row, or null. Pass uuid arguments as [UUID] (see [uuid]). */
    fun Connection.query(sql: String, vararg args: Any?): String? =
        prepareStatement(sql).use { st ->
            args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    fun uuid(id: String): UUID = UUID.fromString(id)
}
