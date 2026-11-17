package com.moxietech.family_tree.state

import com.moxietech.family_tree.api.AccountDto
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.TreeDto
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.MediaId
import com.moxietech.family_tree.domain.Membership
import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.domain.Person
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.Sex
import com.moxietech.family_tree.domain.Tree
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.format.PersonNameFormatter
import com.moxietech.family_tree.permissions.PersonVisibility
import com.moxietech.family_tree.permissions.TreePermissions

/**
 * The signed-in account looking at one tree, applying the same `:core` rules
 * the server enforces, for display only:
 * - [canWrite] decides whether create controls are shown (`TreePermissions`).
 * - [canSeeDetails] decides whether a person shows as the localized private
 *   placeholder (`PersonVisibility`). The server has already redacted that
 *   person; this only stops the UI from presenting the placeholder's
 *   `"Private"` text as if it were a name.
 */
class Viewer(tree: TreeDto, account: AccountDto) {

    private val tree = Tree(TreeId(tree.id), tree.name, AccountId(tree.ownerAccountId), tree.version)
    private val me = AccountId(account.id)

    /**
     * The tree was returned to this account by the API, which requires an
     * ACTIVE membership for every tree read (FT-068), so that is the membership
     * the client can assume.
     */
    val canWrite: Boolean = TreePermissions.canWrite(
        this.tree,
        me,
        Membership(this.tree.id, me, personId = null, status = MembershipStatus.ACTIVE),
    )

    fun canSeeDetails(person: PersonDto): Boolean = PersonVisibility.canSeeDetails(tree, me, person.toDomain())

    /** The formatted name, or null when the UI should show the private placeholder. */
    fun displayName(person: PersonDto): String? = if (canSeeDetails(person)) fullName(person) else null

    companion object {
        fun fullName(person: PersonDto): String =
            PersonNameFormatter.fullName(person.givenNames, person.surnamePaternal, person.surnameMaternal)

        fun sortKey(person: PersonDto): String = PersonNameFormatter.sortKey(person.toDomain())
    }
}

internal fun PersonDto.toDomain(): Person = Person(
    id = PersonId(id),
    treeId = TreeId(treeId),
    givenNames = givenNames,
    surnamePaternal = surnamePaternal,
    surnameMaternal = surnameMaternal,
    sex = Sex.entries.firstOrNull { it.name == sex } ?: Sex.UNKNOWN,
    isLiving = isLiving,
    // FT-008: compared by the merge (PROFILE_PHOTO); null for a hidden person, as the server sends it.
    profileMediaId = profileMediaId?.let(::MediaId),
    bio = bio,
    createdByAccountId = null,
    version = version,
)
