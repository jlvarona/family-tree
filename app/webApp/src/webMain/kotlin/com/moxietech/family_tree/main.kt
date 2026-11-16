package com.moxietech.family_tree

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.moxietech.family_tree.web.FamilyTreeWebApp

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport {
        FamilyTreeWebApp()
    }
}
