package com.moxietech.family_tree.navigation

import io.ktor.http.Parameters
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLParameter
import io.ktor.http.formUrlEncode
import io.ktor.http.parseQueryString
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Which part of a tree the canvas shows. "Side" views come with FT-015. */
enum class TreeViewKind(val token: String) {
    FULL("full"),
    ANCESTORS("ancestors"),
    DESCENDANTS("descendants"),
    ;

    companion object {
        fun fromToken(token: String?): TreeViewKind = entries.firstOrNull { it.token == token } ?: FULL
    }
}

/**
 * The page the user is on, kept in the URL fragment so a reload, a bookmark or
 * the back button lands on the same tree, view, root person or person page:
 *
 * - `#/` — the tree list
 * - `#/trees/{treeId}?view=ancestors&root={personId}` — a tree view
 * - `#/trees/{treeId}/people/{personId}` — a person's page (FT-006)
 *
 * IDs are opaque here; the API validates them and answers `404` for anything
 * that isn't a person or tree the caller can read.
 */
sealed interface Route {
    data object Trees : Route

    data class Tree(
        val treeId: String,
        val view: TreeViewKind = TreeViewKind.FULL,
        val rootPersonId: String? = null,
    ) : Route

    data class Person(val treeId: String, val personId: String) : Route

    /** The fragment, without `#`. */
    fun toFragment(): String = when (this) {
        Trees -> "/"
        is Tree -> buildString {
            append("/trees/").append(treeId.encodeURLParameter())
            val params = Parameters.build {
                if (view != TreeViewKind.FULL) append("view", view.token)
                if (rootPersonId != null) append("root", rootPersonId)
            }
            if (!params.isEmpty()) append('?').append(params.formUrlEncode())
        }
        is Person -> "/trees/${treeId.encodeURLParameter()}/people/${personId.encodeURLParameter()}"
    }

    companion object {
        /** Anything unrecognised (including a sign-in response) opens the tree list. */
        fun parse(fragment: String): Route {
            val text = fragment.removePrefix("#")
            if (!text.startsWith('/')) return Trees
            val path = text.substringBefore('?')
            // A malformed escape (a hand-edited URL) is just an unknown route.
            val (segments, query) = try {
                path.split('/').filter { it.isNotEmpty() }.map { it.decodeURLPart() } to
                    if ('?' in text) parseQueryString(text.substringAfter('?')) else Parameters.Empty
            } catch (e: Exception) {
                return Trees
            }
            return when {
                segments.size == 2 && segments[0] == "trees" && segments[1].isNotBlank() -> Tree(
                    treeId = canonicalId(segments[1]),
                    view = TreeViewKind.fromToken(query["view"]),
                    rootPersonId = query["root"]?.takeIf { it.isNotBlank() }?.let(::canonicalId),
                )
                segments.size == 4 && segments[0] == "trees" && segments[2] == "people" &&
                    segments[1].isNotBlank() && segments[3].isNotBlank() ->
                    Person(canonicalId(segments[1]), canonicalId(segments[3]))
                else -> Trees
            }
        }

        /**
         * A valid UUID in any spelling the API accepts (dashed or hex-only, any
         * case) becomes its canonical dashed lower-case form, the one the API
         * returns; anything else stays as typed, for the API to reject.
         */
        @OptIn(ExperimentalUuidApi::class)
        fun canonicalId(raw: String): String = Uuid.parseOrNull(raw)?.toString() ?: raw

        /** Whether a returned ID is the one requested: UUID identity, else the exact text. */
        fun sameId(returned: String, requested: String): Boolean = canonicalId(returned) == canonicalId(requested)
    }
}
