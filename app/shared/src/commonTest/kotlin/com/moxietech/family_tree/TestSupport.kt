package com.moxietech.family_tree

import com.moxietech.family_tree.api.EventDto
import com.moxietech.family_tree.api.GedcomDateDto
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.PlaceDto
import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.api.UnionChildDto
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.auth.Base64Url
import com.moxietech.family_tree.auth.BrowserBridge
import com.moxietech.family_tree.auth.BrowserFeatureException
import com.moxietech.family_tree.auth.PickedFile
import com.moxietech.family_tree.api.PresignedUploadDto
import com.moxietech.family_tree.client.ApiException
import com.moxietech.family_tree.client.ApiFailure
import com.moxietech.family_tree.client.MediaTransfer

const val TREE_ID = "tree-1"

/** RFC 7636 Appendix B: SHA-256 of the example verifier, as base64url. */
const val RFC7636_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

/**
 * An in-memory browser: URL, sessionStorage, navigations, deterministic "random"
 * bytes, and a stand-in for Web Crypto's digest that returns [digestResult]
 * (no hashing in tests) and records what it was asked to hash.
 */
class FakeBridge(var url: String = "http://localhost:3000/") : BrowserBridge {
    override val origin = "http://localhost:3000"
    val storage = linkedMapOf<String, String>()
    val navigations = mutableListOf<String>()
    val replacedUrls = mutableListOf<String>()
    val digestInputs = mutableListOf<String>()
    var digestResult: ByteArray = Base64Url.decode(RFC7636_CHALLENGE)!!
    var storageBlocked = false
    var cryptoBlocked = false
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

    override fun sessionGet(key: String): String? {
        if (storageBlocked) throw BrowserFeatureException("sessionStorage")
        return storage[key]
    }

    override fun sessionSet(key: String, value: String) {
        if (storageBlocked) throw BrowserFeatureException("sessionStorage")
        storage[key] = value
    }

    override fun sessionRemove(key: String) {
        storage.remove(key)
    }

    override fun randomBytes(count: Int) = ByteArray(count) { (seed++ * 31).toByte() }

    override suspend fun sha256(text: String): ByteArray {
        if (cryptoBlocked) throw BrowserFeatureException("crypto.subtle.digest")
        digestInputs += text
        return digestResult
    }

    /** FT-008: the file the "user" picks next; null simulates cancelling the chooser. */
    var pickedFile: PickedFile? = null
    val pickRequests = mutableListOf<Pair<List<String>, Long>>()

    /** Like the browser bridge: a file over [maxBytes] or of another type is handed over unread. */
    override fun pickFile(accept: List<String>, maxBytes: Long, onPicked: (PickedFile) -> Unit) {
        pickRequests += accept to maxBytes
        val file = pickedFile ?: return
        val readable = file.size <= maxBytes && file.contentType in accept
        onPicked(if (readable) file else PickedFile(file.contentType, file.size, null))
    }
}

/** FT-008: records presigned transfers; each call fails with the next queued failure, if any. */
class FakeTransfer : MediaTransfer {
    val uploads = mutableListOf<Pair<String, Int>>()
    val downloads = mutableListOf<String>()
    val uploadFailures = ArrayDeque<ApiFailure>()
    val downloadFailures = ArrayDeque<ApiFailure>()
    var image: ByteArray = byteArrayOf(1, 2, 3)

    override suspend fun upload(upload: PresignedUploadDto, bytes: ByteArray) {
        uploadFailures.removeFirstOrNull()?.let { throw ApiException(it) }
        uploads += upload.url to bytes.size
    }

    override suspend fun download(url: String): ByteArray {
        downloadFailures.removeFirstOrNull()?.let { throw ApiException(it) }
        downloads += url
        return image
    }
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

/** A hidden living person exactly as the server sends one (FT-066 placeholder, FT-013 `redacted`). */
fun placeholder(id: String) = person(id, given = "Private", living = true).copy(redacted = true)

fun union(id: String, partner1: String, partner2: String?, vararg children: String, type: String = "MARRIED") = UnionDto(
    id = id,
    treeId = TREE_ID,
    partner1Id = partner1,
    partner2Id = partner2,
    type = type,
    startDate = null,
    endDate = null,
    children = children.map { UnionChildDto(it, "BIRTH") },
    version = 0,
)

/** A person event; [date] is the display text, with [earliestIso] as its sortable bound. */
fun event(
    id: String,
    personId: String,
    type: String,
    date: String? = null,
    earliestIso: String? = null,
    place: String? = null,
    notes: String? = null,
    treeId: String = TREE_ID,
    latestIso: String? = earliestIso,
) = EventDto(
    id = id,
    treeId = treeId,
    personId = personId,
    unionId = null,
    type = type,
    date = date?.let { GedcomDateDto(it, earliestIso, latestIso) },
    place = place?.let { PlaceDto("place-$id", it, null, null, null) },
    notes = notes,
    version = 0,
)

fun graph(people: List<String>, vararg unions: UnionDto) =
    TreeGraphDto(TREE_ID, people.map { person(it) }, unions.toList())
