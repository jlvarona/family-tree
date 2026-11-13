package com.moxietech.family_tree

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class ApplicationTest {

    @Test
    fun `unauthenticated request to a protected route is rejected`() = testApplication {
        application {
            module()
        }
        val response = client.get("/api/v1/me")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `unknown route returns 404, not an unhandled exception`() = testApplication {
        application {
            module()
        }
        val response = client.get("/nope")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
