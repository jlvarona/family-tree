package com.moxietech.family_tree.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaRulesTest {

    @Test
    fun onlyJpegAndPngAreSupported() {
        assertEquals(ImageFormat.JPEG, MediaRules.formatOf("image/jpeg"))
        assertEquals(ImageFormat.PNG, MediaRules.formatOf("IMAGE/PNG"))
        for (type in listOf("image/jpg", "image/gif", "image/webp", "image/heic", "image/svg+xml", "image/png; charset=x", "")) {
            assertNull(MediaRules.formatOf(type), type)
        }
    }

    @Test
    fun sizesAndDimensionsAreBounded() {
        assertFalse(MediaRules.isSizeAllowed(0))
        assertTrue(MediaRules.isSizeAllowed(1))
        assertTrue(MediaRules.isSizeAllowed(MediaRules.MAX_UPLOAD_BYTES))
        assertFalse(MediaRules.isSizeAllowed(MediaRules.MAX_UPLOAD_BYTES + 1))

        assertTrue(MediaRules.areDimensionsAllowed(4000, 3000))
        assertFalse(MediaRules.areDimensionsAllowed(0, 10))
        assertFalse(MediaRules.areDimensionsAllowed(MediaRules.MAX_DIMENSION + 1, 1))
        assertFalse(MediaRules.areDimensionsAllowed(10_000, 10_000), "over the pixel cap")
    }

    @Test
    fun purposesAreMatchedExactly() {
        assertEquals(MediaPurpose.PROFILE_PHOTO, MediaRules.purposeOf("PROFILE_PHOTO"))
        assertNull(MediaRules.purposeOf("profile_photo"))
    }
}
