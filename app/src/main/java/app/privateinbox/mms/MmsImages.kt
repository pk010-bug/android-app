package app.privateinbox.mms

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Image handling without third-party loaders (Coil/Glide would pull in network stacks).
 * Re-encoding to JPEG drops ALL metadata (EXIF GPS, camera serials, timestamps) before sending.
 */
object MmsImages {

    /** Downsampled decode for bubbles/viewer; never decodes a full-resolution bitmap. */
    fun decode(file: File, maxEdge: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth.toLong() * bounds.outHeight > 100_000_000L) return null // decompression bomb
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge) }
        BitmapFactory.decodeFile(file.path, opts)
    }.getOrNull()

    fun sampleSize(w: Int, h: Int, maxEdge: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= maxEdge) s *= 2
        return s
    }

    /**
     * Shrinks an image to fit [maxBytes] as a metadata-free JPEG, honouring EXIF rotation.
     * Returns null if it can't get under the limit.
     */
    fun compressForMms(source: ByteArray, maxBytes: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth.toLong() * bounds.outHeight > 100_000_000L) return null
        val rotation = runCatching {
            when (ExifInterface(ByteArrayInputStream(source)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)

        var edge = 1600
        while (edge >= 320) {
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, edge) }
            val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, opts) ?: return null
            val scaled = scaleAndRotate(decoded, edge, rotation)
            for (q in intArrayOf(85, 70, 55, 40)) {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, q, out)
                if (out.size() <= maxBytes) { scaled.recycle(); return out.toByteArray() }
            }
            scaled.recycle()
            edge = edge * 3 / 4
        }
        return null
    }

    private fun scaleAndRotate(b: Bitmap, maxEdge: Int, rotation: Float): Bitmap {
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(b.width, b.height))
        if (scale == 1f && rotation == 0f) return b
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true).also { if (it !== b) b.recycle() }
    }
}
