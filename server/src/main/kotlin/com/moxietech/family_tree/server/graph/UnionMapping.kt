package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.UnionChildDto
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.api.toDto
import com.moxietech.family_tree.domain.Union
import com.moxietech.family_tree.domain.UnionChild

fun Union.toDto(children: List<UnionChild>) = UnionDto(
    id = id.value,
    treeId = treeId.value,
    partner1Id = partner1Id.value,
    partner2Id = partner2Id?.value,
    type = type.name,
    startDate = startDate?.toDto(),
    endDate = endDate?.toDto(),
    children = children.map { UnionChildDto(childId = it.childId.value, relation = it.relation.name) },
    version = version,
)
