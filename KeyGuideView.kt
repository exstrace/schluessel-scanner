package de.ric.schluessel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Zeichnet den Führungsrahmen über die Kameravorschau.
 * Die Vorschau läuft im Format 3:4 (hochkant) mit fitCenter – dadurch entspricht
 * der Rahmen exakt dem Ausschnitt, der später aus dem Foto geschnitten wird.
 */
class KeyGuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        // Rahmen relativ zum Kamerabild (hochkant)
        const val LEFT = 0.04f
        const val RIGHT = 0.96f
        const val TOP = 0.34f
        const val BOTTOM = 0.66f
        const val IMAGE_ASPECT = 3f / 4f   // Breite / Höhe
    }

    private val density = resources.displayMetrics.density
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
    }
    private val center = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1 * density
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8 * density, 8 * density), 0f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14 * density
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val guide = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0 || vh <= 0) return

        val cw: Float
        val ch: Float
        if (vw / vh > IMAGE_ASPECT) { ch = vh; cw = vh * IMAGE_ASPECT } else { cw = vw; ch = vw / IMAGE_ASPECT }
        val cx = (vw - cw) / 2f
        val cy = (vh - ch) / 2f

        guide.set(cx + LEFT * cw, cy + TOP * ch, cx + RIGHT * cw, cy + BOTTOM * ch)
        val r = 12 * density

        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, vw, vh, Path.Direction.CW)
        path.addRoundRect(guide, r, r, Path.Direction.CW)
        canvas.drawPath(path, dim)
        canvas.drawRoundRect(guide, r, r, stroke)
        canvas.drawLine(guide.left + r, guide.centerY(), guide.right - r, guide.centerY(), center)
        canvas.drawText("Schlüssel waagerecht · Bart vollständig im Rahmen", vw / 2f, guide.bottom + 24 * density, text)
    }
}
