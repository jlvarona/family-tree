package com.moxietech.family_tree.server.media

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import javax.imageio.ImageIO

/**
 * FT-008 test images, built in code so every fixture is reproducible from the
 * committed sources: JDK-encoded JPEG and PNG files, plus the metadata a phone or an
 * editor would add (Exif with a GPS IFD, a JFXX thumbnail carrying its own Exif, PNG
 * text chunks) and deliberately corrupted variants.
 */
internal object TestImages {

    /** Bytes that only appear inside the injected location metadata. */
    const val GPS_MARKER = "FT008-GPS-AREA-40.4168N-3.7038W"

    /** The GPS latitude rationals 40/1, 25/1, 0/1, as stored in the GPS IFD. */
    val GPS_LATITUDE: ByteArray = ByteBuffer.allocate(24).putInt(40).putInt(1).putInt(25).putInt(1).putInt(0).putInt(1).array()

    /** The orientation entry the cleaner writes for orientation 6 (rotate 90° clockwise). */
    val ORIENTATION_6_ENTRY = byteArrayOf(0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6)

    fun jpeg(width: Int = 64, height: Int = 48): ByteArray = encode(picture(width, height), "jpg")

    fun png(width: Int = 64, height: Int = 48): ByteArray = encode(picture(width, height), "png")

    /** A phone-like JPEG: Exif APP1 with orientation 6 and a GPS IFD, after the JFIF header. */
    fun jpegWithGps(width: Int = 64, height: Int = 48): ByteArray =
        insertAfterApp0(jpeg(width, height), segment(0xE1, exifWithGps(orientation = 6)))

    /**
     * A JFIF 1.02 file whose JFIF header has a 1×1 thumbnail and that carries a JFXX
     * extension (code 0x10: JPEG thumbnail) whose embedded JPEG has its own Exif GPS IFD.
     */
    fun jpegWithJfxxThumbnail(): ByteArray {
        val base = jpeg()
        val app0End = 4 + u16(base, 4)
        val jfifPayload = base.copyOfRange(6, app0End)
        // JFIF header with a 1×1 RGB thumbnail instead of 0×0.
        val withThumb = jfifPayload.copyOf(14) + byteArrayOf(0x11, 0x22, 0x33)
        withThumb[12] = 1
        withThumb[13] = 1
        val thumbnail = jpegWithGps(8, 8)
        val jfxx = "JFXX".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0x10) + thumbnail
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + segment(0xE0, withThumb) + segment(0xE0, jfxx) + base.copyOfRange(app0End, base.size)
    }

    /** A JPEG whose scan stops halfway, then an end-of-image marker: structurally whole, pixels missing. */
    fun jpegWithTruncatedScan(): ByteArray {
        val base = jpeg(128, 96)
        val sos = indexOf(base, byteArrayOf(0xFF.toByte(), 0xDA.toByte()))
        val dataStart = sos + 2 + u16(base, sos + 2)
        val dataEnd = base.size - 2
        return base.copyOf(dataStart + (dataEnd - dataStart) / 2) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    /** A PNG with text, Exif and time chunks before its pixels. */
    fun pngWithMetadata(): ByteArray = insertPngChunks(
        png(),
        chunk("tEXt", "Comment\u0000$GPS_MARKER".toByteArray(Charsets.ISO_8859_1)),
        chunk("eXIf", exifWithGps(orientation = 1).copyOfRange(6, exifWithGps(orientation = 1).size)),
        chunk("iTXt", "XML:com.adobe.xmp\u0000\u0000\u0000\u0000\u0000<x:xmpmeta>$GPS_MARKER</x:xmpmeta>".toByteArray(Charsets.ISO_8859_1)),
        chunk("tIME", byteArrayOf(0x07, 0xEA.toByte(), 11, 16, 12, 0, 0)),
    )

    /**
     * Every chunk well formed with a correct CRC, but the IDAT's zlib stream starts with
     * a reserved block type, so the pixels can't be inflated.
     */
    fun pngWithCorruptIdat(): ByteArray {
        val base = png()
        val idat = indexOf(base, "IDAT".toByteArray(Charsets.US_ASCII)) - 4
        val length = u32(base, idat).toInt()
        val corrupt = base.copyOf()
        for (i in idat + 8 + 2 until idat + 8 + length) corrupt[i] = 0xFF.toByte()
        val crc = CRC32().apply { update(corrupt, idat + 4, 4 + length) }.value
        ByteBuffer.wrap(corrupt, idat + 8 + length, 4).putInt(crc.toInt())
        return corrupt
    }

    /** A PNG declaring [width]×[height] with a valid header CRC (the pixel data is the original's). */
    fun pngClaiming(width: Int, height: Int): ByteArray {
        val base = png()
        val out = base.copyOf()
        ByteBuffer.wrap(out, 16, 8).putInt(width).putInt(height)
        val crc = CRC32().apply { update(out, 12, 17) }.value
        ByteBuffer.wrap(out, 29, 4).putInt(crc.toInt())
        return out
    }

    fun withUnknownCriticalChunk(): ByteArray = insertPngChunks(png(), chunk("QUUX", byteArrayOf(1, 2, 3)))

    fun contains(haystack: ByteArray, needle: ByteArray): Boolean = indexOf(haystack, needle) >= 0

    fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    fun decode(bytes: ByteArray): BufferedImage? = ImageIO.read(bytes.inputStream())

    /** Exif payload ("Exif\0\0" + big-endian TIFF): IFD0 with orientation and a GPS pointer; a GPS IFD with latitude and an area string. */
    private fun exifWithGps(orientation: Int): ByteArray {
        val marker = GPS_MARKER.toByteArray(Charsets.US_ASCII)
        val tiff = ByteBuffer.allocate(104 + marker.size)
        tiff.put('M'.code.toByte()).put('M'.code.toByte()).putShort(42).putInt(8)
        // IFD0 at 8: 2 entries, ends at 38.
        tiff.putShort(2)
        tiff.putShort(0x0112).putShort(3).putInt(1).putShort(orientation.toShort()).putShort(0)
        tiff.putShort(0x8825.toShort()).putShort(4).putInt(1).putInt(38)
        tiff.putInt(0)
        // GPS IFD at 38: 3 entries, ends at 80.
        tiff.putShort(3)
        tiff.putShort(0x0001).putShort(2).putInt(2).put('N'.code.toByte()).put(0).putShort(0)
        tiff.putShort(0x0002).putShort(5).putInt(3).putInt(80)
        tiff.putShort(0x001C).putShort(7).putInt(marker.size).putInt(104)
        tiff.putInt(0)
        tiff.put(GPS_LATITUDE) // 80..104
        tiff.put(marker)
        return byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0) + tiff.array()
    }

    private fun picture(width: Int, height: Int): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            for (y in 0 until height) for (x in 0 until width) setRGB(x, y, ((x * 4) shl 16) or ((y * 5) shl 8) or ((x + y) and 0xFF))
        }

    private fun encode(image: BufferedImage, format: String): ByteArray =
        ByteArrayOutputStream().also { check(ImageIO.write(image, format, it)) }.toByteArray()

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    private fun insertAfterApp0(jpeg: ByteArray, vararg segments: ByteArray): ByteArray {
        check(jpeg[2] == 0xFF.toByte() && jpeg[3] == 0xE0.toByte()) { "expected a JFIF APP0 first" }
        val at = 4 + u16(jpeg, 4)
        return jpeg.copyOf(at) + segments.reduce(ByteArray::plus) + jpeg.copyOfRange(at, jpeg.size)
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(typeBytes); update(data) }.value
        return ByteBuffer.allocate(12 + data.size).putInt(data.size).put(typeBytes).put(data).putInt(crc.toInt()).array()
    }

    /** Inserts chunks right after IHDR (8-byte signature + 25-byte IHDR chunk). */
    private fun insertPngChunks(png: ByteArray, vararg chunks: ByteArray): ByteArray =
        png.copyOf(33) + chunks.reduce(ByteArray::plus) + png.copyOfRange(33, png.size)

    private fun u16(bytes: ByteArray, at: Int) = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private fun u32(bytes: ByteArray, at: Int): Long = ByteBuffer.wrap(bytes, at, 4).int.toLong() and 0xFFFFFFFFL
}
