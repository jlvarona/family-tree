package com.moxietech.family_tree.api

import com.moxietech.family_tree.domain.GedcomDate

fun GedcomDate.toDto(): GedcomDateDto = GedcomDateDto(
    displayText = displayText,
    earliestIso = earliest?.toString(),
    latestIso = latest?.toString(),
)

fun GedcomDateDto.displayTextOrNull(): String? = displayText.ifEmpty { null }
