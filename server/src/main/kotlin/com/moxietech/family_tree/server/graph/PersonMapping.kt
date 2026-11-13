package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.domain.Person
import com.moxietech.family_tree.format.PersonNameFormatter

fun Person.toDto() = PersonDto(
    id = id.value,
    treeId = treeId.value,
    givenNames = givenNames,
    surnamePaternal = surnamePaternal,
    surnameMaternal = surnameMaternal,
    fullName = PersonNameFormatter.fullName(this),
    sex = sex.name,
    isLiving = isLiving,
    profileMediaId = profileMediaId?.value,
    bio = bio,
    version = version,
)

/**
 * Living-person privacy (AGENTS.md "Living-person privacy"): outside a
 * grant covering them, a living person shows as "Private" with no dates,
 * photos or bio. Branch-scoped grants land in Phase 2; until then the
 * conservative, rule-correct default is "visible in full to the tree
 * owner only" rather than silently showing everyone everything.
 */
fun PersonDto.redacted() = copy(
    givenNames = "Private",
    surnamePaternal = null,
    surnameMaternal = null,
    fullName = "Private",
    profileMediaId = null,
    bio = null,
)
