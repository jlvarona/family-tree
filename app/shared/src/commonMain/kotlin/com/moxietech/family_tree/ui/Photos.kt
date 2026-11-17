package com.moxietech.family_tree.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * FT-008: an image fetched from photo storage, cropped to [size] in [shape] (or
 * fitted whole, for a citation scan, with [contentScale] `Fit`), or [fallback] when it
 * can't be decoded. The bytes are the server-cleaned JPEG or PNG (metadata removed
 * except orientation) from a short-lived presigned URL.
 */
@Composable
internal fun StoredPhoto(
    bytes: ByteArray,
    description: String,
    size: Int,
    shape: Shape,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: @Composable () -> Unit,
) {
    val bitmap = remember(bytes) { decode(bytes) }
    if (bitmap == null) {
        fallback()
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = description,
            contentScale = contentScale,
            modifier = Modifier.size(size.dp).clip(shape),
        )
    }
}

@OptIn(ExperimentalResourceApi::class)
private fun decode(bytes: ByteArray): ImageBitmap? = runCatching { bytes.decodeToImageBitmap() }.getOrNull()
