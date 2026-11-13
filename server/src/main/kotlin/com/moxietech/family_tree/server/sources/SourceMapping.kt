package com.moxietech.family_tree.server.sources

import com.moxietech.family_tree.api.SourceDto
import com.moxietech.family_tree.api.toDto
import com.moxietech.family_tree.domain.Source

fun Source.toDto() = SourceDto(
    id = id.value,
    treeId = treeId.value,
    title = title,
    type = type.name,
    author = author,
    publisher = publisher,
    repository = repository,
    url = url,
    date = date?.toDto(),
    version = version,
)
