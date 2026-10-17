package de.ric.schluessel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Erkennung über Bildmerkmale (ORB) des Barts:
 *  1. Foto → Graustufen → einheitliche Breite → Kontrastausgleich (CLAHE)
 *  2. ORB-Merkmale (Ecken/Kanten der Einschnitte und Fräsungen)
 *  3. Abgleich mit jedem gespeicherten Foto, Ratio-Test,
 *     danach geometrische Prüfung (RANSAC, Drehung/Verschiebung/leichte Skalierung).
 *  Punktzahl = Anzahl geometrisch passender Merkmale.
 *  Bei zwei Seiten werden beide Zuordnungen (A↔A/B↔B und vertauscht) geprüft.
 */
object KeyMatcher {

    private const val FORMAT_VERSION = 3        // bei Änderung der Parameter erhöhen → Merkmale werden neu berechnet
    private const val WORK_WIDTH = 800
    private const val N_FEATURES = 700
    private const val RATIO = 0.78f
    private const val MIN_FEATURES = 15
    private const val MIN_GOOD = 8
    private const val RANSAC_PX = 6.0

    /** Schwellen pro Seite (Anzahl passender Merkmale). Bei Bedarf an eigene Schlüssel anpassen. */
    const val SURE_PER_SIDE = 22.0
    const val LIKELY_PER_SIDE = 11.0

    @Volatile
    private var ready = false

    fun init(): Boolean {
        if (!ready) ready = OpenCVLoader.initLocal()
        return ready
    }

    class Features(val pts: FloatArray, val desc: Mat) {
        val count: Int get() = desc.rows()
    }

    data class MatchResult(val key: KeyEntry, val score: Int, val crossed: Boolean)

    enum class Level { SURE, LIKELY, NONE }

    private val cache = ConcurrentHashMap<String, Features>()

    // ---------------------------------------------------------------- Merkmale

    fun extract(src: Bitmap): Features {
        val bmp = if (src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, false)
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)

        val scale = WORK_WIDTH.toDouble() / gray.cols()
        val small = Mat()
        Imgproc.resize(
            gray, small,
            Size(WORK_WIDTH.toDouble(), Math.round(gray.rows() * scale).toDouble()),
            0.0, 0.0,
            if (scale < 1.0) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR
        )
        val eq = Mat()
        Imgproc.createCLAHE(2.0, Size(8.0, 8.0)).apply(small, eq)
        val blur = Mat()
        Imgproc.GaussianBlur(eq, blur, Size(3.0, 3.0), 0.0)

        val kps = MatOfKeyPoint()
        val desc = Mat()
        ORB.create(N_FEATURES).detectAndCompute(blur, Mat(), kps, desc)

        val arr = kps.toArray()
        val n = desc.rows()
        val pts = FloatArray(n * 2)
        for (i in 0 until minOf(n, arr.size)) {
            pts[2 * i] = arr[i].pt.x.toFloat()
            pts[2 * i + 1] = arr[i].pt.y.toFloat()
        }
        rgba.release(); gray.release(); small.release(); eq.release(); blur.release(); kps.release()
        val d = if (n == 0) Mat(0, 32, CvType.CV_8U) else desc
        return Features(pts, d)
    }

    fun extractFile(file: File): Features? {
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return extract(bmp)
    }

    /** Liefert die Merkmale eines gespeicherten Fotos (Cache → .orb-Datei → neu berechnen). */
    fun featuresFor(imageName: String): Features? {
        cache[imageName]?.let { return it }
        val ff = KeyRepository.featureFile(imageName)
        val f = (if (ff.exists()) runCatching { load(ff) }.getOrNull() else null) ?: run {
            val bmp = BitmapFactory.decodeFile(KeyRepository.imageFile(imageName).absolutePath) ?: return null
            extract(bmp).also { runCatching { save(it, ff) } }
        }
        cache[imageName] = f
        return f
    }

    fun preload(keys: List<KeyEntry>) {
        for (k in keys) for (img in k.images) featuresFor(img.file)
    }

    fun evict(name: String) {
        cache.remove(name)?.desc?.release()
    }

    private fun save(f: Features, file: File) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            val n = f.count
            out.writeInt(FORMAT_VERSION)
            out.writeInt(n)
            for (i in 0 until n * 2) out.writeFloat(f.pts[i])
            val bytes = ByteArray(n * 32)
            if (n > 0) f.desc.get(0, 0, bytes)
            out.write(bytes)
        }
    }

    private fun load(file: File): Features {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { inp ->
            if (inp.readInt() != FORMAT_VERSION) throw IOException("Veraltetes Format")
            val n = inp.readInt()
            val pts = FloatArray(n * 2) { inp.readFloat() }
            val bytes = ByteArray(n * 32)
            inp.readFully(bytes)
            val desc = Mat(n, 32, CvType.CV_8U)
            if (n > 0) desc.put(0, 0, bytes)
            return Features(pts, desc)
        }
    }

    // ---------------------------------------------------------------- Vergleich

    private fun score(q: Features, t: Features, matcher: BFMatcher): Int {
        if (q.count < MIN_FEATURES || t.count < MIN_FEATURES) return 0
        val knn = ArrayList<MatOfDMatch>()
        matcher.knnMatch(q.desc, t.desc, knn, 2)

        val src = ArrayList<Point>()
        val dst = ArrayList<Point>()
        for (m in knn) {
            val a = m.toArray()
            if (a.size >= 2 && a[0].distance < RATIO * a[1].distance) {
                val qi = a[0].queryIdx
                val ti = a[0].trainIdx
                src.add(Point(q.pts[2 * qi].toDouble(), q.pts[2 * qi + 1].toDouble()))
                dst.add(Point(t.pts[2 * ti].toDouble(), t.pts[2 * ti + 1].toDouble()))
            }
            m.release()
        }
        if (src.size < MIN_GOOD) return 0

        val s = MatOfPoint2f(); s.fromList(src)
        val d = MatOfPoint2f(); d.fromList(dst)
        val inliers = Mat()
        val model = Calib3d.estimateAffinePartial2D(s, d, inliers, Calib3d.RANSAC, RANSAC_PX)

        var n = 0
        if (!model.empty() && !inliers.empty()) {
            // Gleicher Bildausschnitt → Maßstab muss etwa 1 sein, sonst Zufallstreffer
            val a = model.get(0, 0)[0]
            val b = model.get(1, 0)[0]
            val scale = sqrt(a * a + b * b)
            if (scale in 0.65..1.55) n = Core.countNonZero(inliers)
        }
        s.release(); d.release(); inliers.release(); model.release()
        return n
    }

    private fun best(q: Features, list: List<Features>, matcher: BFMatcher): Int =
        list.maxOfOrNull { score(q, it, matcher) } ?: 0

    /**
     * Vergleicht die Aufnahme(n) mit allen Schlüsseln.
     * @param qA Foto der ersten Seite (Pflicht)
     * @param qB Foto der zweiten Seite (optional)
     */
    suspend fun identify(
        qA: Features,
        qB: Features?,
        keys: List<KeyEntry>,
        onProgress: (Int, Int) -> Unit
    ): List<MatchResult> = coroutineScope {
        val total = keys.size
        if (total == 0) return@coroutineScope emptyList()
        val done = AtomicInteger()
        val workers = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 6)
        val chunkSize = (total + workers - 1) / workers

        keys.chunked(chunkSize).map { chunk ->
            async(Dispatchers.Default) {
                val matcher = BFMatcher.create(Core.NORM_HAMMING, false)
                chunk.map { key ->
                    ensureActive()
                    val a = key.images.filter { it.side == SIDE_A }.mapNotNull { featuresFor(it.file) }
                    val b = key.images.filter { it.side == SIDE_B }.mapNotNull { featuresFor(it.file) }
                    val result = if (qB != null) {
                        val straight = best(qA, a, matcher) + best(qB, b, matcher)
                        val crossed = best(qA, b, matcher) + best(qB, a, matcher)
                        if (crossed > straight) MatchResult(key, crossed, true) else MatchResult(key, straight, false)
                    } else {
                        val sa = best(qA, a, matcher)
                        val sb = best(qA, b, matcher)
                        MatchResult(key, max(sa, sb), sb > sa)
                    }
                    onProgress(done.incrementAndGet(), total)
                    result
                }
            }
        }.awaitAll().flatten().sortedByDescending { it.score }
    }

    fun verdict(results: List<MatchResult>, sides: Int): Level {
        val best = results.firstOrNull()?.score ?: 0
        val second = results.getOrNull(1)?.score ?: 0
        val perSide = best.toDouble() / sides
        return when {
            perSide >= SURE_PER_SIDE && best >= second * 1.5 -> Level.SURE
            perSide >= LIKELY_PER_SIDE -> Level.LIKELY
            else -> Level.NONE
        }
    }
}
