package com.moxietech.family_tree.navigation

import com.moxietech.family_tree.auth.ParsedUrl
import com.moxietech.family_tree.auth.UrlCodec

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
 * - `#/trees/{treeId}/people/{personId}` — a person's page (FT-006 builds it out)
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
            append("/trees/").append(UrlCodec.encode(treeId))
            val params = buildList {
                if (view != TreeViewKind.FULL) add("view" to view.token)
                if (rootPersonId != null) add("root" to rootPersonId)
            }
            if (params.isNotEmpty()) append('?').append(UrlCodec.query(params))
        }
        is Person -> "/trees/${UrlCodec.encode(treeId)}/people/${UrlCodec.encode(personId)}"
    }

    companion object {
        /** Anything unrecognised opens the tree list. */
        fun parse(fragment: String): Route {
            val text = fragment.removePrefix("#")
            val queryIndex = text.indexOf('?')
            val path = if (queryIndex >= 0) text.substring(0, queryIndex) else text
            val query = if (queryIndex >= 0) ParsedUrl.parseQuery(text.substring(queryIndex + 1)) else emptyMap()
            val segments = path.split('/').filter { it.isNotEmpty() }.map(UrlCodec::decode)
            return when {
                segments.size == 2 && segments[0] == "trees" && segments[1].isNotBlank() -> Tree(
                    treeId = segments[1],
                    view = TreeViewKind.fromToken(query["view"]),
                    rootPersonId = query["root"]?.takeIf { it.isNotBlank() },
                )
                segments.size == 4 && segments[0] == "trees" && segments[2] == "people" &&
                    segments[1].isNotBlank() && segments[3].isNotBlank() -> Person(segments[1], segments[3])
                else -> Trees
            }
        }
    }
}
