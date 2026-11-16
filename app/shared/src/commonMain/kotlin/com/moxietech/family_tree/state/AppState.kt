package com.moxietech.family_tree.state

import com.moxietech.family_tree.api.AccountDto
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.TreeDto
import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.auth.AuthState
import com.moxietech.family_tree.client.ApiFailure
import com.moxietech.family_tree.domain.ChildRelation
import com.moxietech.family_tree.domain.Sex
import com.moxietech.family_tree.domain.UnionType
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
)

data class BranchKey(val view: TreeViewKind, val rootPersonId: String)

/** The person destination FT-006 builds into the full person page. */
data class PersonScreen(
    val treeId: String,
    val personId: String,
    val instance: Long = 0,
    val tree: Loadable<TreeDto> = Loadable.Loading,
    val person: Loadable<PersonDto> = Loadable.Loading,
)

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
    /** The person was created; the server's duplicate check found these near-matches. */
    data class PossibleDuplicates(val names: List<String?>) : Notice

    data object ChildLinked : Notice
}

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
