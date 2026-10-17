package de.ric.schluessel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Nimmt nacheinander Seite A (Vorderseite) und Seite B (Rückseite) auf.
 * Rückgabe: Pfade zu den zugeschnittenen Fotos (EXTRA_A, optional EXTRA_B).
 */
class CaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_ENROLL = "enroll"
        const val MODE_IDENTIFY = "identify"
        const val EXTRA_A = "a"
        const val EXTRA_B = "b"

        fun intent(ctx: Context, mode: String): Intent =
            Intent(ctx, CaptureActivity::class.java).putExtra(EXTRA_MODE, mode)
    }

    private lateinit var previewView: PreviewView
    private lateinit var tvStep: TextView
    private lateinit var btnCapture: MaterialButton
    private lateinit var btnTorch: MaterialButton
    private lateinit var btnSkip: MaterialButton
    private lateinit var reviewPanel: View
    private lateinit var ivReview: ImageView
    private lateinit var progress: ProgressBar

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var torchOn = false
    private var step = 0
    private val files = arrayOfNulls<File>(2)
    private var pending: File? = null
    private lateinit var mode: String

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else {
            toast("Ohne Kamerazugriff ist keine Erkennung möglich.")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_IDENTIFY

        previewView = findViewById(R.id.previewView)
        tvStep = findViewById(R.id.tvStep)
        btnCapture = findViewById(R.id.btnCapture)
        btnTorch = findViewById(R.id.btnTorch)
        btnSkip = findViewById(R.id.btnSkip)
        reviewPanel = findViewById(R.id.reviewPanel)
        ivReview = findViewById(R.id.ivReview)
        progress = findViewById(R.id.progress)

        btnCapture.setOnClickListener { takePhoto() }
        btnTorch.setOnClickListener {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
            btnTorch.text = if (torchOn) "Licht aus" else "Licht an"
        }
        btnSkip.setOnClickListener { finishWithResult() }
        findViewById<MaterialButton>(R.id.btnRetake).setOnClickListener {
            pending?.delete()
            pending = null
            reviewPanel.visibility = View.GONE
        }
        findViewById<MaterialButton>(R.id.btnAccept).setOnClickListener { accept() }

        // Antippen = auf diese Stelle scharfstellen
        previewView.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                val p = previewView.meteringPointFactory.createPoint(ev.x, ev.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(p).build())
                v.performClick()
            }
            true
        }

        updateStep()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun updateStep() {
        val verb = if (mode == MODE_ENROLL) "einlernen" else "erkennen"
        tvStep.text = if (step == 0) {
            "Schritt 1 von 2 · Seite A (Vorderseite) $verb\nSchlüssel flach auf ruhigen Untergrund, Bart im Rahmen"
        } else {
            "Schritt 2 von 2 · Seite B (Rückseite) $verb\nSchlüssel umdrehen, gleiche Lage wie eben"
        }
        btnSkip.visibility = if (step == 1) View.VISIBLE else View.GONE
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            @Suppress("DEPRECATION")
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            @Suppress("DEPRECATION")
            val capture = ImageCapture.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            imageCapture = capture
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                camera?.cameraControl?.enableTorch(torchOn)
            } catch (e: Exception) {
                toast("Kamera konnte nicht gestartet werden: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        btnCapture.isEnabled = false
        progress.visibility = View.VISIBLE
        ic.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val bmp = try { image.toBitmap() } finally { image.close() }
                lifecycleScope.launch {
                    val file = withContext(Dispatchers.Default) {
                        val cropped = ImageUtil.cropGuide(ImageUtil.rotate(bmp, rotation))
                        val out = ImageUtil.scaleToWidth(cropped, 1200)
                        val f = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                        ImageUtil.saveJpeg(out, f)
                        f
                    }
                    progress.visibility = View.GONE
                    btnCapture.isEnabled = true
                    pending = file
                    ivReview.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
                    reviewPanel.visibility = View.VISIBLE
                }
            }

            override fun onError(exception: ImageCaptureException) {
                progress.visibility = View.GONE
                btnCapture.isEnabled = true
                toast("Aufnahme fehlgeschlagen: ${exception.message}")
            }
        })
    }

    private fun accept() {
        files[step] = pending
        pending = null
        reviewPanel.visibility = View.GONE
        if (step == 0) {
            step = 1
            updateStep()
        } else {
            finishWithResult()
        }
    }

    private fun finishWithResult() {
        val a = files[0]
        if (a == null) {
            setResult(RESULT_CANCELED)
        } else {
            val data = Intent().putExtra(EXTRA_A, a.absolutePath)
            files[1]?.let { data.putExtra(EXTRA_B, it.absolutePath) }
            setResult(RESULT_OK, data)
        }
        finish()
    }
}
