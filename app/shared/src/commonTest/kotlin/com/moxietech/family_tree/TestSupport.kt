package com.moxietech.family_tree

import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.api.UnionChildDto
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.auth.BrowserBridge

const val TREE_ID = "tree-1"

/** An in-memory browser: URL, sessionStorage, navigations and deterministic "random" bytes. */
class FakeBridge(var url: String = "http://localhost:3000/") : BrowserBridge {
    override val origin = "http://localhost:3000"
    val storage = linkedMapOf<String, String>()
    val navigations = mutableListOf<String>()
    val replacedUrls = mutableListOf<String>()
    private val hashListeners = mutableListOf<() -> Unit>()
    private var seed = 1

    override fun currentUrl() = url

    override fun replaceUrl(url: String) {
        replacedUrls += url
        this.url = url
    }

    override fun navigate(url: String) {
        navigations += url
    }

    override fun setHash(hash: String) {
        url = url.substringBefore('#') + "#" + hash
    }

    override fun onHashChange(listener: () -> Unit) {
        hashListeners += listener
    }

    /** Simulates the back button or a typed URL. */
    fun changeHash(hash: String) {
        setHash(hash)
        hashListeners.forEach { it() }
    }

    override fun sessionGet(key: String) = storage[key]

    override fun sessionSet(key: String, value: String) {
        storage[key] = value
    }

    override fun sessionRemove(key: String) {
        storage.remove(key)
    }

    override fun randomBytes(count: Int) = ByteArray(count) { (seed++ * 31).toByte() }
}

fun person(id: String, given: String = id, living: Boolean = false, paternal: String? = null) = PersonDto(
    id = id,
    treeId = TREE_ID,
    givenNames = given,
    surnamePaternal = paternal,
    surnameMaternal = null,
    fullName = given,
    sex = "UNKNOWN",
    isLiving = living,
    profileMediaId = null,
    bio = null,
    version = 0,
)

fun union(id: String, partner1: String, partner2: String?, vararg children: String) = UnionDto(
    id = id,
    treeId = TREE_ID,
    partner1Id = partner1,
    partner2Id = partner2,
    type = "MARRIED",
    startDate = null,
    endDate = null,
    children = children.map { UnionChildDto(it, "BIRTH") },
    version = 0,
)

fun graph(people: List<String>, vararg unions: UnionDto) =
    TreeGraphDto(TREE_ID, people.map { person(it) }, unions.toList())
