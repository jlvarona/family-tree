package com.moxietech.family_tree.state

import com.moxietech.family_tree.api.AccountDto
import com.moxietech.family_tree.api.EventDto
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.TreeDto
import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.auth.AuthState
import com.moxietech.family_tree.client.ApiFailure
import com.moxietech.family_tree.domain.ChildRelation
import com.moxietech.family_tree.domain.Sex
import com.moxietech.family_tree.domain.UnionType
import com.moxietech.family_tree.merge.MergeField
import com.moxietech.family_tree.navigation.Route
import com.moxietech.family_tree.navigation.TreeViewKind

sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>

    data class Ready<T>(val value: T) : Loadable<T>

    data class Failed(val failure: ApiFailure) : Loadable<Nothing>
}

val <T> Loadable<T>.valueOrNull: T?
    get() = (this as? Loadable.Ready<T>)?.value

/**
 * One tree's canvas: the bundle, the chosen view and root, and the selected person.
 * [instance] identifies this opening of the screen, so a late response for an
 * earlier visit to the same tree is not applied.
 */
data class TreeScreen(
    val treeId: String,
    val instance: Long = 0,
    val tree: Loadable<TreeDto> = Loadable.Loading,
    val graph: Loadable<TreeGraphDto> = Loadable.Loading,
    val view: TreeViewKind = TreeViewKind.FULL,
    val rootPersonId: String? = null,
    /** People in the ancestor/descendant branch of the root; null for the full view or no root. */
    val branch: Loadable<Set<String>>? = null,
    val branchKey: BranchKey? = null,
    val selectedPersonId: String? = null,
    /** The result of the owner's last action on this tree; dropped with the screen. */
    val notice: Notice? = null,
    /** The owner's merge in progress (FT-007); dropped with the screen, the session or on success. */
    val merge: MergeState? = null,
)

/**
 * The owner merging two records of one person (FT-007). [id] identifies this
 * opening of the merge, so a late response for an earlier one is dropped.
 *
 * The owner picks [firstId] and [secondId]; both are then fetched fresh
 * ([records]) so the versions sent are the ones reviewed. [survivorId] is the record
 * kept. [choices] holds, for each differing field, the ID of the person whose value
 * is kept; fields that are the same in both need none. [retiredConfirmed] is the
 * owner's explicit confirmation of which record is removed, and is cleared whenever
 * the survivor changes.
 */
data class MergeState(
    val id: Long,
    val firstId: String? = null,
    val secondId: String? = null,
    val records: Loadable<MergeRecords>? = null,
    /**
     * Identifies the latest read of [records]. Only that read may fill them, so a
     * delayed answer from an earlier comparison of the same pair (A/B, A/C, back to
     * A/B) can't replace the newer one.
     */
    val recordsLoad: Long = 0,
    val survivorId: String? = null,
    val choices: Map<MergeField, String> = emptyMap(),
    val retiredConfirmed: Boolean = false,
    val submitting: Boolean = false,
    val error: MergeError? = null,
)

/** The two records as just read from the server, in the order the owner picked them. */
data class MergeRecords(val first: PersonDto, val second: PersonDto) {
    fun byId(id: String?): PersonDto? = listOf(first, second).firstOrNull { it.id == id }

    fun other(id: String?): PersonDto? = when (id) {
        first.id -> second
        second.id -> first
        else -> null
    }
}

enum class MergeError {
    SAME_PERSON,
    SURVIVOR_REQUIRED,
    CHOICES_REQUIRED,
    CONFIRMATION_REQUIRED,

    /** The tree's relationships stop these two from becoming one (see `PersonMerge.relationshipConflicts`). */
    RELATIONSHIPS,

    /** A `409`: a record changed or can't be merged as shown; both were reloaded to review again. */
    CHANGED,
    FORBIDDEN,
    NOT_FOUND,
    INVALID,
    UNAVAILABLE,
    SERVER,
}

data class BranchKey(val view: TreeViewKind, val rootPersonId: String)

/**
 * One opening of a person's page (FT-006). [tree] and [person] decide whether
 * the page can render at all; [graph] (the FT-004 bundle, for family links)
 * and [events] (the person's timeline) load afterwards and fail on their own,
 * so a failed section shows its own retry without hiding the rest.
 */
data class PersonScreen(
    val treeId: String,
    val personId: String,
    val instance: Long = 0,
    val tree: Loadable<TreeDto> = Loadable.Loading,
    val person: Loadable<PersonDto> = Loadable.Loading,
    val graph: Loadable<TreeGraphDto> = Loadable.Loading,
    val events: Loadable<List<EventDto>> = Loadable.Loading,
    /** The owner's profile-photo change in progress or failed (FT-008); dropped with the page. */
    val photo: PhotoUpload? = null,
)

/**
 * FT-008: one profile-photo change on a person page. [busy] while requests run;
 * [error] after a failure. [pending] keeps what a retry needs so it resumes where it
 * stopped: the chosen file, and the upload's media ID once it was sent to the store
 * (a retry then finalizes and attaches it instead of uploading again). [removing] is
 * a "remove photo" request rather than an upload.
 */
data class PhotoUpload(
    val busy: Boolean = false,
    val error: PhotoError? = null,
    val pending: PendingPhoto? = null,
    val removing: Boolean = false,
)

class PendingPhoto(val contentType: String, val bytes: ByteArray, val mediaId: String? = null) {
    fun withMedia(id: String?) = PendingPhoto(contentType, bytes, id)
}

enum class PhotoError {
    /** Not a JPEG or PNG (checked before anything is sent). */
    UNSUPPORTED_TYPE,

    /** Over `MediaRules.MAX_UPLOAD_BYTES` (checked before anything is sent). */
    TOO_LARGE,

    /** The server didn't accept the file as a valid image; choose another. */
    INVALID_IMAGE,

    /** The person changed meanwhile; the page was reloaded and a retry uses the new version. */
    CHANGED,

    /** Sending the file to photo storage failed (network, CORS, expired link); retry uploads again. */
    UPLOAD_FAILED,
    FORBIDDEN,
    NOT_FOUND,
    UNAVAILABLE,
    SERVER,
}

/** An image cached for display, per tree and media ID (FT-008). */
data class PhotoKey(val treeId: String, val mediaId: String)

enum class FormError {
    NAME_REQUIRED,
    PARENT_REQUIRED,
    CHILD_REQUIRED,

    /** The parents have several unions (or the chosen one no longer matches): pick one. */
    UNION_REQUIRED,
    SAME_PARENT_TWICE,
    CHILD_IS_PARENT,
    ALREADY_LINKED,
    FORBIDDEN,
    NOT_FOUND,
    INVALID,
    UNAVAILABLE,
    SERVER,
}

/**
 * [createdUnionId] is set when linking a child created a new union but the
 * child request then failed: the form switches to that union so a retry adds
 * the child to it instead of creating another.
 */
data class FormState(
    val submitting: Boolean = false,
    val error: FormError? = null,
    val createdUnionId: String? = null,
)

sealed interface Notice {
    /**
     * The person was created; the server's duplicate check found these near-matches,
     * each of which the owner can review for a merge with [createdPersonId] (FT-007).
     */
    data class PossibleDuplicates(val createdPersonId: String, val candidates: List<DuplicateMatch>) : Notice

    data object ChildLinked : Notice
}

/** One near-match from the duplicate check; [name] is null when the UI should show the private placeholder. */
data class DuplicateMatch(val personId: String, val name: String?)

/**
 * Everything the web UI renders. Every field except [auth] and [route] holds
 * the signed-in user's data and is reset whenever the session ends or changes.
 */
data class AppState(
    val auth: AuthState = AuthState.Starting,
    val route: Route = Route.Trees,
    val account: Loadable<AccountDto> = Loadable.Loading,
    val trees: Loadable<List<TreeDto>> = Loadable.Loading,
    val treeScreen: TreeScreen? = null,
    val personScreen: PersonScreen? = null,
    val form: FormState = FormState(),
    /**
     * FT-008: images fetched through short-lived presigned URLs, as encoded bytes (the
     * UI decodes them). Only images the server let this session read; cleared with the
     * session like everything else, and capped in size.
     */
    val photos: Map<PhotoKey, Loadable<ByteArray>> = emptyMap(),
)

data class PersonInput(
    val givenNames: String,
    val surnamePaternal: String,
    val surnameMaternal: String,
    val sex: Sex,
    val isLiving: Boolean,
)

/** Which union a child is linked through: one the parents already have, or a new one. */
sealed interface UnionChoice {
    data class Existing(val unionId: String) : UnionChoice

    data class New(val type: UnionType) : UnionChoice
}

data class LinkChildInput(
    val parentId: String?,
    val otherParentId: String?,
    /** Null when the parents have several unions and none was picked. */
    val union: UnionChoice?,
    val childId: String?,
    val relation: ChildRelation,
)
