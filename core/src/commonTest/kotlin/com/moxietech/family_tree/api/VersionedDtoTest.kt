package com.moxietech.family_tree.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * FT-001: places and child links gained a version. JSON written before that
 * still decodes, the version is always sent (the server's Json doesn't encode
 * defaults), and update requests can't omit it.
 */
class VersionedDtoTest {

    /** Like the server's content negotiation and the web client: defaults are not encoded. */
    private val lenient = Json { ignoreUnknownKeys = true }

    @Test
    fun placeAndChildLinkJsonWithoutVersionStillDecodes() {
        val place = lenient.decodeFromString<PlaceDto>(
            """{"id":"p","name":"Sevilla","hierarchy":null,"latitude":null,"longitude":null}""",
        )
        assertEquals(1, place.version)
        val child = lenient.decodeFromString<UnionChildDto>("""{"childId":"c","relation":"BIRTH"}""")
        assertEquals(1, child.version)
    }

    @Test
    fun versionIsEncodedEvenWhenItIsTheDefault() {
        val place = lenient.encodeToJsonElement(PlaceDto.serializer(), PlaceDto("p", "Sevilla", null, null, null)).jsonObject
        assertEquals(1, place.getValue("version").jsonPrimitive.long)
        val child = lenient.encodeToJsonElement(UnionChildDto.serializer(), UnionChildDto("c", "BIRTH")).jsonObject
        assertEquals(1, child.getValue("version").jsonPrimitive.long)
    }

    @Test
    fun updateRequestsRequireAVersion() {
        assertFailsWith<IllegalArgumentException> { lenient.decodeFromString<UpdatePlaceRequest>("""{"name":"Sevilla"}""") }
        assertFailsWith<IllegalArgumentException> { lenient.decodeFromString<UpdateUnionChildRequest>("""{"relation":"BIRTH"}""") }
        assertFailsWith<IllegalArgumentException> {
            lenient.decodeFromString<UpdatePersonRequest>("""{"givenNames":"Ana","sex":"FEMALE","isLiving":false}""")
        }
        assertFailsWith<IllegalArgumentException> { lenient.decodeFromString<UpdateSourceRequest>("""{"title":"T","type":"OTHER"}""") }
        assertFailsWith<IllegalArgumentException> { lenient.decodeFromString<UpdateEventRequest>("""{"type":"BIRTH"}""") }
        assertFailsWith<IllegalArgumentException> {
            lenient.decodeFromString<UpdateUnionRequest>("""{"partner1Id":"a","type":"MARRIED"}""")
        }
    }
}
