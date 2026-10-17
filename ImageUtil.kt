package de.ric.schluessel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream

object ImageUtil {

    private val thumbs = LruCache<String, Bitmap>(80)

    fun rotate(b: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return b
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    /** Schneidet den Bereich des Führungsrahmens aus dem (hochkant gedrehten) Foto aus. */
    fun cropGuide(b: Bitmap): Bitmap {
        val l = (b.width * KeyGuideView.LEFT).toInt()
        val t = (b.height * KeyGuideView.TOP).toInt()
        val r = (b.width * KeyGuideView.RIGHT).toInt()
        val bt = (b.height * KeyGuideView.BOTTOM).toInt()
        return Bitmap.createBitmap(b, l, t, (r - l).coerceAtLeast(1), (bt - t).coerceAtLeast(1))
    }

    fun scaleToWidth(b: Bitmap, width: Int): Bitmap {
        if (b.width <= width) return b
        val h = (b.height * width / b.width.toFloat()).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(b, width, h, true)
    }

    fun saveJpeg(b: Bitmap, f: File) {
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }

    fun decodeSampled(f: File, reqWidth: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= reqWidth) sample *= 2
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun thumb(name: String): Bitmap? {
        thumbs.get(name)?.let { return it }
        val b = decodeSampled(KeyRepository.imageFile(name), 300) ?: return null
        thumbs.put(name, b)
        return b
    }

    fun evictThumb(name: String) {
        thumbs.remove(name)
    }
}
