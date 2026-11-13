package com.moxietech.family_tree

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform