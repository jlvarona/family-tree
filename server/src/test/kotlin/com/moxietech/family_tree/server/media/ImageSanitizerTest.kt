package com.moxietech.family_tree.server.media

import com.moxietech.family_tree.media.ImageFormat
import com.moxietech.family_tree.server.media.TestImages.GPS_LATITUDE
import com.moxietech.family_tree.server.media.TestImages.GPS_MARKER
import com.moxietech.family_tree.server.media.TestImages.ORIENTATION_6_ENTRY
import com.moxietech.family_tree.server.media.TestImages.contains
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** FT-008: verification and metadata removal, on images built in code ([TestImages]); no containers. */
class ImageSanitizerTest {

    private fun clean(bytes: ByteArray, format: ImageFormat): CleanImage =
        assertIs<SanitizeResult.Clean>(ImageSanitizer.sanitize(bytes, format), "expected a clean image").image

    private fun invalid(bytes: ByteArray, format: ImageFormat): InvalidImage =
        assertIs<SanitizeResult.Invalid>(ImageSanitizer.sanitize(bytes, format), "expected a rejection").reason

    private val marker = GPS_MARKER.toByteArray(Charsets.US_ASCII)

    @Test
    fun `a phone JPEG loses its Exif GPS IFD and keeps its orientation, size and pixels`() {
        val input = TestImages.jpegWithGps()
        assertTrue(contains(input, marker) && contains(input, GPS_LATITUDE), "fixture carries GPS")

        val image = clean(input, ImageFormat.JPEG)

        assertFalse(contains(image.bytes, marker), "GPS area string removed")
        assertFalse(contains(image.bytes, GPS_LATITUDE), "GPS latitude removed")
        assertFalse(contains(image.bytes, byteArrayOf(0x88.toByte(), 0x25, 0, 4)), "no GPS IFD pointer")
        assertTrue(contains(image.bytes, ORIENTATION_6_ENTRY), "orientation 6 carried over")
        assertEquals(64 to 48, image.width to image.height)
        val decoded = assertNotNull(TestImages.decode(image.bytes))
        assertEquals(64 to 48, decoded.width to decoded.height)
        // The scan data is copied unchanged: same pixels as the original file.
        val original = TestImages.decode(input)!!
        assertEquals(original.getRGB(10, 10), decoded.getRGB(10, 10))
    }

    @Test
    fun `a JFXX thumbnail with nested Exif GPS is dropped and the JFIF header is rebuilt without a thumbnail`() {
        val input = TestImages.jpegWithJfxxThumbnail()
        assertTrue(contains(input, marker) && contains(input, "JFXX".toByteArray()), "fixture carries a JFXX thumbnail with GPS")

        val image = clean(input, ImageFormat.JPEG)

        assertFalse(contains(image.bytes, marker), "nested GPS removed")
        assertFalse(contains(image.bytes, GPS_LATITUDE))
        assertFalse(contains(image.bytes, "JFXX".toByteArray()), "JFXX extension removed")
        assertFalse(contains(image.bytes, byteArrayOf(0x11, 0x22, 0x33)), "JFIF thumbnail pixels removed")
        // SOI, then exactly one canonical JFIF APP0: length 16, thumbnail 0×0.
        assertEquals(listOf(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10), image.bytes.take(6).map { it.toInt() and 0xFF })
        assertEquals("JFIF", String(image.bytes, 6, 4, Charsets.US_ASCII))
        assertEquals(0 to 0, image.bytes[18].toInt() to image.bytes[19].toInt())
        assertNotNull(TestImages.decode(image.bytes))
    }

    @Test
    fun `a JPEG whose scan data ends early is rejected even though its markers are complete`() {
        assertEquals(InvalidImage.MALFORMED, invalid(TestImages.jpegWithTruncatedScan(), ImageFormat.JPEG))
    }

    @Test
    fun `a PNG with CRC-valid chunks but a corrupt zlib stream is rejected`() {
        val corrupt = TestImages.pngWithCorruptIdat()
        // The JDK decoder refuses it too, independently of the sanitizer.
        assertTrue(runCatching { TestImages.decode(corrupt) }.let { it.isFailure || it.getOrNull() == null })
        assertEquals(InvalidImage.MALFORMED, invalid(corrupt, ImageFormat.PNG))
    }

    @Test
    fun `PNG text, Exif and time chunks are dropped and the image still decodes`() {
        val input = TestImages.pngWithMetadata()
        assertTrue(contains(input, marker))

        val image = clean(input, ImageFormat.PNG)

        for (type in listOf("tEXt", "eXIf", "iTXt", "tIME")) assertFalse(contains(image.bytes, type.toByteArray()), type)
        assertFalse(contains(image.bytes, marker))
        assertEquals(64 to 48, image.width to image.height)
        assertNotNull(TestImages.decode(image.bytes))
    }

    @Test
    fun `wrong, truncated, oversized and unknown-critical images are rejected`() {
        assertEquals(InvalidImage.WRONG_FORMAT, invalid(TestImages.jpeg(), ImageFormat.PNG), "declared PNG, sent JPEG")
        assertEquals(InvalidImage.WRONG_FORMAT, invalid(TestImages.png(), ImageFormat.JPEG))
        assertEquals(InvalidImage.WRONG_FORMAT, invalid("GIF89a....".toByteArray(), ImageFormat.JPEG))
        val jpeg = TestImages.jpeg()
        assertEquals(InvalidImage.TRUNCATED, invalid(jpeg.copyOf(jpeg.size / 2), ImageFormat.JPEG))
        val png = TestImages.png()
        assertEquals(InvalidImage.TRUNCATED, invalid(png.copyOf(png.size - 6), ImageFormat.PNG))
        assertEquals(InvalidImage.TOO_LARGE, invalid(TestImages.pngClaiming(20_000, 1), ImageFormat.PNG))
        assertEquals(InvalidImage.MALFORMED, invalid(TestImages.withUnknownCriticalChunk(), ImageFormat.PNG))
    }

    @Test
    fun `large images are decoded into a bounded subsampled destination`() {
        assertEquals(1, PixelVerifier.subsampling(1_000_000))
        assertEquals(8, PixelVerifier.subsampling(50_000_000))
        val image = clean(TestImages.png(3000, 2000), ImageFormat.PNG) // 6 MP: decoded at step 3
        assertEquals(3000 to 2000, image.width to image.height)
    }
}
