package com.moxietech.family_tree.server.media

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.event.IIOReadWarningListener
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * FT-008: decodes every pixel of a cleaned image with the JDK's ImageIO readers, to
 * prove the compressed data is valid, without ever holding the full image in memory.
 *
 * The reader is asked for a subsampled destination of at most [MAX_DESTINATION_PIXELS]
 * pixels. Both readers still decode the whole stream to produce it (PNG inflates and
 * unfilters every row; JPEG entropy-decodes every scanline), so a broken zlib stream,
 * a short row or damaged entropy data is found, while the allocation stays bounded
 * (about 4 MB) whatever the image size. Decoder warnings (libjpeg reports corrupt data
 * and premature ends as warnings) count as failures. The decoded result is thrown away:
 * the stored bytes are the cleaned file, not a re-encoding.
 *
 * Unsupported by the JDK readers, so rejected: CMYK/YCCK JPEGs and 12-bit JPEGs.
 */
internal object PixelVerifier {

    const val MAX_DESTINATION_PIXELS = 1_000_000L

    init {
        // No temporary files for the image streams.
        ImageIO.setUseCache(false)
    }

    fun decodes(image: CleanImage): Boolean {
        val stream = ImageIO.createImageInputStream(ByteArrayInputStream(image.bytes)) ?: return false
        stream.use {
            val reader: ImageReader = ImageIO.getImageReadersByMIMEType(image.format.contentType).asSequence().firstOrNull()
                ?: return false
            try {
                var warned = false
                reader.addIIOReadWarningListener(IIOReadWarningListener { _, _ -> warned = true })
                reader.setInput(stream, true, true)
                if (reader.getNumImages(false).let { it != -1 && it < 1 }) return false
                if (reader.getWidth(0) != image.width || reader.getHeight(0) != image.height) return false
                val step = subsampling(image.width.toLong() * image.height)
                val param = reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) }
                val decoded = reader.read(0, param)
                return !warned && decoded != null
            } catch (e: Exception) {
                // IIOException, EOFException, zlib errors, unsupported colour models...
                return false
            } catch (e: OutOfMemoryError) {
                return false
            } finally {
                reader.dispose()
            }
        }
    }

    /** The smallest square subsampling step that keeps the destination within the cap. */
    internal fun subsampling(pixels: Long): Int =
        if (pixels <= MAX_DESTINATION_PIXELS) 1 else ceil(sqrt(pixels.toDouble() / MAX_DESTINATION_PIXELS)).toInt()
}
