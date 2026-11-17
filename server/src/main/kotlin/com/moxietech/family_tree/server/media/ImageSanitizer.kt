package com.moxietech.family_tree.server.media

import com.moxietech.family_tree.media.ImageFormat
import com.moxietech.family_tree.media.MediaRules
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** Why an upload is not accepted as an image. */
enum class InvalidImage {
    /** Not a JPEG or PNG at all, or not the format that was declared. */
    WRONG_FORMAT,

    /** The structure is broken or uses features this verifier doesn't accept. */
    MALFORMED,

    /** The file ends before the image does (an incomplete upload). */
    TRUNCATED,

    /** Wider, taller or larger in pixels than [MediaRules] allows. */
    TOO_LARGE,
}

/** A verified image with its metadata removed, ready to store. */
class CleanImage(val bytes: ByteArray, val format: ImageFormat, val width: Int, val height: Int)

sealed interface SanitizeResult {
    class Clean(val image: CleanImage) : SanitizeResult

    data class Invalid(val reason: InvalidImage) : SanitizeResult
}

/**
 * FT-008 verification and metadata privacy for uploaded images, run before an
 * upload can become readable.
 *
 * The whole file is parsed. It must be the declared format and complete (a JPEG
 * that reaches its end-of-image marker, a PNG whose every chunk passes its CRC
 * and that reaches `IEND`), with dimensions inside [MediaRules]. The output is
 * rebuilt from an allow-list of structural parts, so metadata is dropped by
 * default rather than by recognising it:
 *
 * - **JPEG:** keeps the frame, tables, scans, restart intervals, ICC colour profiles
 *   (`APP2 ICC_PROFILE`) and Adobe colour info (`APP14`), and rebuilds the first JFIF
 *   `APP0` as a canonical 16-byte header with a 0×0 thumbnail (version, units and
 *   densities copied). Drops JFXX extensions (JPEG/palette/RGB thumbnails, which can
 *   embed their own Exif and GPS) and any other `APP0` payload, Exif (including GPS),
 *   XMP, IPTC/Photoshop, MPF secondary images, comments, every other application
 *   segment and anything after the end-of-image marker.
 *   The Exif orientation alone is carried over in a minimal, newly written Exif
 *   block, so photos aren't shown rotated.
 * - **PNG:** keeps the critical chunks and colour/transparency/physical-size
 *   chunks. Drops `eXIf`, all text chunks (`tEXt`, `zTXt`, `iTXt`, which carry XMP),
 *   `tIME`, animation chunks (the default image stays) and unknown ancillary
 *   chunks; an unknown critical chunk is rejected.
 *
 * The pixel data is copied unchanged (no re-encoding, so no quality loss). The
 * cleaned file is then decoded in full by [PixelVerifier], so a file whose envelope is
 * valid but whose compressed pixels are corrupt (a CRC-valid PNG with a broken zlib
 * stream, a JPEG with damaged entropy data) is rejected.
 *
 * CPU- and memory-bound, no I/O; callers limit how many run at once (`MediaService`).
 */
object ImageSanitizer {

    fun sanitize(bytes: ByteArray, declared: ImageFormat): SanitizeResult {
        val detected = detect(bytes) ?: return SanitizeResult.Invalid(InvalidImage.WRONG_FORMAT)
        if (detected != declared) return SanitizeResult.Invalid(InvalidImage.WRONG_FORMAT)
        return try {
            val image = when (detected) {
                ImageFormat.JPEG -> Jpeg(bytes).clean()
                ImageFormat.PNG -> Png(bytes).clean()
            }
            when {
                !MediaRules.areDimensionsAllowed(image.width, image.height) -> SanitizeResult.Invalid(InvalidImage.TOO_LARGE)
                // The structure is only the envelope: every pixel must also decode.
                !PixelVerifier.decodes(image) -> SanitizeResult.Invalid(InvalidImage.MALFORMED)
                else -> SanitizeResult.Clean(image)
            }
        } catch (e: InvalidImageException) {
            SanitizeResult.Invalid(e.reason)
        }
    }

    /** The format from the file's signature, ignoring what the client said. */
    fun detect(bytes: ByteArray): ImageFormat? = when {
        bytes.size >= 3 && bytes.u8(0) == 0xFF && bytes.u8(1) == 0xD8 && bytes.u8(2) == 0xFF -> ImageFormat.JPEG
        bytes.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] } -> ImageFormat.PNG
        else -> null
    }

    private class InvalidImageException(val reason: InvalidImage) : RuntimeException(reason.name)

    private fun fail(reason: InvalidImage): Nothing = throw InvalidImageException(reason)

    // --- JPEG ---------------------------------------------------------------------------

    private class Jpeg(private val input: ByteArray) {
        private val n = input.size
        private var orientation = 1
        private var width = 0
        private var height = 0

        /** Kept segments and scan data, in order, as byte ranges of [input]. */
        private val kept = mutableListOf<IntRange>()

        /** The rewritten canonical JFIF APP0 (no thumbnail), when the file has one. */
        private var jfif: ByteArray? = null

        fun clean(): CleanImage {
            var pos = 2 // after SOI
            var frameSeen = false
            var scanSeen = false
            while (true) {
                if (pos >= n) fail(InvalidImage.TRUNCATED)
                if (input.u8(pos) != 0xFF) fail(InvalidImage.MALFORMED)
                while (pos < n && input.u8(pos) == 0xFF) pos++ // fill bytes
                if (pos >= n) fail(InvalidImage.TRUNCATED)
                val marker = input.u8(pos)
                val markerStart = pos - 1
                pos++
                when {
                    marker == EOI -> {
                        if (!frameSeen || !scanSeen) fail(InvalidImage.MALFORMED)
                        break // anything after EOI (trailers, appended data) is dropped
                    }
                    marker == SOI || marker == 0x00 || marker == 0x01 || marker in RST -> fail(InvalidImage.MALFORMED)
                    else -> {
                        if (pos + 2 > n) fail(InvalidImage.TRUNCATED)
                        val length = input.u16be(pos)
                        if (length < 2) fail(InvalidImage.MALFORMED)
                        val end = pos + length
                        if (end > n) fail(InvalidImage.TRUNCATED)
                        val payload = pos + 2 until end
                        val segment = markerStart until end
                        when {
                            // Only the first canonical JFIF header is carried over, rebuilt without
                            // its thumbnail; JFXX extensions (thumbnails that can embed their own
                            // Exif, GPS included) and any other APP0 payload are dropped.
                            marker == APP0 -> if (jfif == null && startsWith(payload, JFIF_ID) && payload.count() >= 14) {
                                jfif = canonicalJfif(payload.first + JFIF_ID.size)
                            }
                            marker == APP1 -> if (startsWith(payload, EXIF_ID)) orientation = readOrientation(payload.first + EXIF_ID.size, end)
                            marker == APP2 -> if (startsWith(payload, ICC_ID)) keep(segment)
                            marker == APP14 -> keep(segment)
                            marker in APP_MARKERS || marker == COM -> Unit // other metadata: dropped
                            marker in SOF -> {
                                if (frameSeen) fail(InvalidImage.MALFORMED)
                                if (length < 8) fail(InvalidImage.MALFORMED)
                                height = input.u16be(payload.first + 1)
                                width = input.u16be(payload.first + 3)
                                if (width == 0 || height == 0) fail(InvalidImage.MALFORMED)
                                frameSeen = true
                                keep(segment)
                            }
                            marker in TABLES -> keep(segment)
                            marker == SOS -> {
                                if (!frameSeen) fail(InvalidImage.MALFORMED)
                                keep(segment)
                                val dataEnd = scanEnd(end)
                                if (dataEnd > end) keep(end until dataEnd)
                                pos = dataEnd
                                scanSeen = true
                                continue
                            }
                            else -> fail(InvalidImage.MALFORMED) // reserved / extension markers
                        }
                        pos = end
                    }
                }
            }

            val out = ByteArrayOutputStream(n)
            out.write(0xFF)
            out.write(SOI)
            // JFIF requires APP0 straight after SOI.
            jfif?.let { out.write(it) }
            if (orientation != 1) out.write(orientationApp1(orientation))
            kept.forEach { out.write(input, it.first, it.count()) }
            out.write(0xFF)
            out.write(EOI)
            return CleanImage(out.toByteArray(), ImageFormat.JPEG, width, height)
        }

        private fun keep(range: IntRange) {
            kept += range
        }

        /**
         * A 16-byte-payload JFIF APP0 (JFIF 1.02, w3.org/Graphics/JPEG/jfif3.pdf) with the
         * version, density units and densities read at [fields], and a 0×0 thumbnail.
         */
        private fun canonicalJfif(fields: Int): ByteArray {
            val header = input.copyOfRange(fields, fields + 7) // version (2), units (1), Xdensity (2), Ydensity (2)
            val length = 2 + JFIF_ID.size + header.size + 2
            return byteArrayOf(0xFF.toByte(), APP0.toByte(), 0, length.toByte()) + JFIF_ID + header + byteArrayOf(0, 0)
        }

        /** The end of entropy-coded data starting at [start]: the next marker that isn't stuffing or a restart. */
        private fun scanEnd(start: Int): Int {
            var i = start
            while (true) {
                if (i >= n) fail(InvalidImage.TRUNCATED)
                if (input.u8(i) != 0xFF) {
                    i++
                    continue
                }
                if (i + 1 >= n) fail(InvalidImage.TRUNCATED)
                val next = input.u8(i + 1)
                when {
                    next == 0x00 || next in RST -> i += 2
                    next == 0xFF -> i += 1 // fill byte before a marker
                    else -> return i
                }
            }
        }

        private fun startsWith(range: IntRange, prefix: ByteArray): Boolean =
            range.count() >= prefix.size && prefix.indices.all { input[range.first + it] == prefix[it] }

        /** The Exif orientation (1-8) from a TIFF structure in [start, end); 1 when absent or unreadable. */
        private fun readOrientation(start: Int, end: Int): Int {
            fun u16(at: Int, little: Boolean) = if (little) input.u8(at) or (input.u8(at + 1) shl 8) else input.u16be(at)
            fun u32(at: Int, little: Boolean): Long =
                if (little) {
                    (input.u8(at).toLong()) or (input.u8(at + 1).toLong() shl 8) or (input.u8(at + 2).toLong() shl 16) or (input.u8(at + 3).toLong() shl 24)
                } else {
                    (input.u8(at).toLong() shl 24) or (input.u8(at + 1).toLong() shl 16) or (input.u8(at + 2).toLong() shl 8) or input.u8(at + 3).toLong()
                }
            if (end - start < 8) return 1
            val little = when {
                input.u8(start) == 'I'.code && input.u8(start + 1) == 'I'.code -> true
                input.u8(start) == 'M'.code && input.u8(start + 1) == 'M'.code -> false
                else -> return 1
            }
            if (u16(start + 2, little) != 42) return 1
            val ifd = start + u32(start + 4, little)
            if (ifd + 2 > end || ifd < start) return 1
            val count = u16(ifd.toInt(), little)
            for (index in 0 until count) {
                val entry = ifd.toInt() + 2 + index * 12
                if (entry + 12 > end) return 1
                if (u16(entry, little) == 0x0112 && u16(entry + 2, little) == 3) {
                    val value = u16(entry + 8, little)
                    return if (value in 1..8) value else 1
                }
            }
            return 1
        }

        /** A minimal Exif APP1 holding only the orientation tag (big-endian TIFF, one IFD entry). */
        private fun orientationApp1(value: Int): ByteArray {
            val tiff = byteArrayOf(
                'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, // header, IFD0 at offset 8
                0, 1, // one entry
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, value.toByte(), 0, 0, // Orientation, SHORT, count 1, value
                0, 0, 0, 0, // no next IFD
            )
            val length = 2 + EXIF_ID.size + tiff.size
            return byteArrayOf(0xFF.toByte(), APP1.toByte(), (length shr 8).toByte(), length.toByte()) + EXIF_ID + tiff
        }
    }

    // --- PNG ----------------------------------------------------------------------------

    private class Png(private val input: ByteArray) {
        fun clean(): CleanImage {
            val n = input.size
            val out = ByteArrayOutputStream(n)
            out.write(PNG_SIGNATURE)
            var pos = PNG_SIGNATURE.size
            var width = 0L
            var height = 0L
            var headerSeen = false
            var dataSeen = false
            val crc = CRC32()
            while (true) {
                if (pos + 8 > n) fail(InvalidImage.TRUNCATED)
                val length = input.u32be(pos)
                if (length > Int.MAX_VALUE) fail(InvalidImage.MALFORMED)
                val typeStart = pos + 4
                val type = String(input, typeStart, 4, Charsets.ISO_8859_1)
                if (!type.all { it in 'a'..'z' || it in 'A'..'Z' }) fail(InvalidImage.MALFORMED)
                val dataStart = typeStart + 4
                val end = dataStart.toLong() + length + 4
                if (end > n) fail(InvalidImage.TRUNCATED)
                crc.reset()
                crc.update(input, typeStart, 4 + length.toInt())
                if (crc.value != input.u32be(dataStart + length.toInt())) fail(InvalidImage.MALFORMED)
                if (!headerSeen && type != "IHDR") fail(InvalidImage.MALFORMED)
                when (type) {
                    "IHDR" -> {
                        if (headerSeen || length != 13L) fail(InvalidImage.MALFORMED)
                        width = input.u32be(dataStart)
                        height = input.u32be(dataStart + 4)
                        if (width == 0L || height == 0L || width > Int.MAX_VALUE || height > Int.MAX_VALUE) fail(InvalidImage.MALFORMED)
                        headerSeen = true
                    }
                    "IDAT" -> dataSeen = true
                }
                val critical = type[0].isUpperCase()
                when {
                    type in PNG_KEPT -> out.write(input, pos, (end - pos).toInt())
                    critical -> fail(InvalidImage.MALFORMED) // unknown critical chunk: can't be dropped safely
                    else -> Unit // ancillary metadata: dropped
                }
                pos = end.toInt()
                if (type == "IEND") break // anything after IEND is dropped
            }
            if (!dataSeen) fail(InvalidImage.MALFORMED)
            return CleanImage(out.toByteArray(), ImageFormat.PNG, width.toInt(), height.toInt())
        }
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private val PNG_KEPT = setOf(
        "IHDR", "PLTE", "IDAT", "IEND", // critical
        "tRNS", "cHRM", "gAMA", "iCCP", "sBIT", "sRGB", "cICP", "mDCV", "cLLI", "bKGD", "pHYs", // rendering only
    )

    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val SOS = 0xDA
    private const val COM = 0xFE
    private const val APP0 = 0xE0
    private const val APP1 = 0xE1
    private const val APP2 = 0xE2
    private const val APP14 = 0xEE
    private val APP_MARKERS = 0xE0..0xEF
    private val RST = 0xD0..0xD7

    /**
     * Baseline, extended and progressive Huffman frames: what browsers and the pixel
     * check ([PixelVerifier]) decode. Lossless, arithmetic and hierarchical frames are
     * rejected as malformed.
     */
    private val SOF = setOf(0xC0, 0xC1, 0xC2)

    /** DHT, DAC, DQT, DNL, DRI, EXP. */
    private val TABLES = setOf(0xC4, 0xCC, 0xDB, 0xDC, 0xDD, 0xDF)

    private val EXIF_ID = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
    private val ICC_ID = "ICC_PROFILE".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
    private val JFIF_ID = "JFIF".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
}

private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

private fun ByteArray.u16be(at: Int): Int = (u8(at) shl 8) or u8(at + 1)

private fun ByteArray.u32be(at: Int): Long =
    (u8(at).toLong() shl 24) or (u8(at + 1).toLong() shl 16) or (u8(at + 2).toLong() shl 8) or u8(at + 3).toLong()
