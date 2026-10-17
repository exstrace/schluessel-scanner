package de.ric.schluessel

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ID = "id"
        fun intent(ctx: Context, id: String): Intent =
            Intent(ctx, DetailActivity::class.java).putExtra(EXTRA_ID, id)
    }

    private lateinit var key: KeyEntry
    private lateinit var fields: KeyFields
    private lateinit var container: LinearLayout

    private val photosLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            val a = res.data?.getStringExtra(CaptureActivity.EXTRA_A) ?: return@registerForActivityResult
            addPhotos(File(a), res.data?.getStringExtra(CaptureActivity.EXTRA_B)?.let { File(it) })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        KeyRepository.init(this)

        val k = intent.getStringExtra(EXTRA_ID)?.let { KeyRepository.get(it) }
        if (k == null) { finish(); return }
        key = k
        title = key.name

        fields = KeyFields(findViewById(R.id.fields))
        fields.fill(key)
        container = findViewById(R.id.imagesContainer)

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            if (!fields.validate()) return@setOnClickListener
            fields.applyTo(key)
            KeyRepository.save()
            title = key.name
            toast("Gespeichert.")
        }
        findViewById<MaterialButton>(R.id.btnAddPhotos).setOnClickListener {
            photosLauncher.launch(CaptureActivity.intent(this, CaptureActivity.MODE_ENROLL))
        }
        findViewById<MaterialButton>(R.id.btnDelete).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("„${key.name}“ löschen?")
                .setMessage("Der Schlüssel und alle Fotos werden entfernt.")
                .setPositiveButton("Löschen") { _, _ ->
                    KeyRepository.delete(key)
                    finish()
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }
        renderImages()
    }

    private fun renderImages() {
        container.removeAllViews()
        for (img in key.images.sortedBy { it.side }) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, dp(8), 0)
            }
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(170), dp(90))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(ImageUtil.thumb(img.file))
                setOnClickListener { showFull(img) }
                setOnLongClickListener { confirmRemove(img); true }
            }
            val label = TextView(this).apply {
                text = if (img.side == SIDE_A) "Seite A" else "Seite B"
                setPadding(0, dp(4), 0, 0)
            }
            cell.addView(iv)
            cell.addView(label)
            container.addView(cell)
        }
        findViewById<TextView>(R.id.tvImagesInfo).text =
            "Fotos (${key.count(SIDE_A)}× Seite A, ${key.count(SIDE_B)}× Seite B) – lange drücken zum Entfernen"
    }

    private fun showFull(img: KeyImage) {
        val iv = ImageView(this).apply {
            adjustViewBounds = true
            setImageBitmap(ImageUtil.decodeSampled(KeyRepository.imageFile(img.file), 1200))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (img.side == SIDE_A) "Seite A" else "Seite B")
            .setView(iv)
            .setPositiveButton("Schließen", null)
            .show()
    }

    private fun confirmRemove(img: KeyImage) {
        if (key.images.size <= 1) {
            toast("Das letzte Foto kann nicht entfernt werden.")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Foto entfernen?")
            .setPositiveButton("Entfernen") { _, _ ->
                KeyRepository.removeImage(key, img)
                renderImages()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun addPhotos(a: File, b: File?) {
        val pd = progressDialog("Merkmale werden berechnet…")
        lifecycleScope.launch {
            try {
                val imgs = withContext(Dispatchers.Default) {
                    listOfNotNull(
                        KeyRepository.importPhoto(key.id, a, SIDE_A),
                        b?.let { KeyRepository.importPhoto(key.id, it, SIDE_B) }
                    )
                }
                key.images.addAll(imgs)
                KeyRepository.save()
                renderImages()
            } catch (e: Exception) {
                toast("Fehler: ${e.message}")
            } finally {
                a.delete(); b?.delete()
                pd.dismiss()
            }
        }
    }
}
