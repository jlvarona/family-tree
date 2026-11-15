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

