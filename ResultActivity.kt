package de.ric.schluessel

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ResultActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_A = "qa"
        private const val EXTRA_B = "qb"

        fun intent(ctx: Context, a: String, b: String?): Intent =
            Intent(ctx, ResultActivity::class.java).putExtra(EXTRA_A, a).putExtra(EXTRA_B, b)
    }

    private var fileA: File? = null
    private var fileB: File? = null

    private lateinit var ivA: ImageView
    private lateinit var ivB: ImageView
    private lateinit var pb: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var card: MaterialCardView
    private lateinit var tvVerdict: TextView
    private lateinit var tvVerdictDetail: TextView
    private lateinit var adapter: ResultAdapter

    private val rescanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            val a = res.data?.getStringExtra(CaptureActivity.EXTRA_A) ?: return@registerForActivityResult
            deleteQueries()
            fileA = File(a)
            fileB = res.data?.getStringExtra(CaptureActivity.EXTRA_B)?.let { File(it) }
            showQueries()
            startMatching()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)
        KeyRepository.init(this)
        KeyMatcher.init()

        fileA = intent.getStringExtra(EXTRA_A)?.let { File(it) }
        fileB = intent.getStringExtra(EXTRA_B)?.let { File(it) }

        ivA = findViewById(R.id.ivQueryA)
        ivB = findViewById(R.id.ivQueryB)
        pb = findViewById(R.id.pbProgress)
        tvStatus = findViewById(R.id.tvStatus)
        card = findViewById(R.id.cardVerdict)
        tvVerdict = findViewById(R.id.tvVerdict)
        tvVerdictDetail = findViewById(R.id.tvVerdictDetail)

        adapter = ResultAdapter(
            onOpen = { startActivity(DetailActivity.intent(this, it.id)) },
            onConfirm = { confirm(it) }
        )
        findViewById<RecyclerView>(R.id.rvResults).apply {
            layoutManager = LinearLayoutManager(this@ResultActivity)
            adapter = this@ResultActivity.adapter
        }
        findViewById<MaterialButton>(R.id.btnRescan).setOnClickListener {
            rescanLauncher.launch(CaptureActivity.intent(this, CaptureActivity.MODE_IDENTIFY))
        }

        if (fileA == null) { finish(); return }
        showQueries()
        startMatching()
    }

    private fun showQueries() {
        ivA.setImageBitmap(fileA?.let { BitmapFactory.decodeFile(it.absolutePath) })
        val b = fileB
        if (b != null) {
            ivB.visibility = View.VISIBLE
            ivB.setImageBitmap(BitmapFactory.decodeFile(b.absolutePath))
        } else {
            ivB.visibility = View.GONE
        }
    }

    private fun startMatching() {
        val a = fileA ?: return
        val b = fileB
        card.visibility = View.GONE
        adapter.submit(emptyList())
        pb.visibility = View.VISIBLE
        pb.isIndeterminate = true
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "Analysiere Aufnahme…"

        lifecycleScope.launch {
            try {
                val keys = KeyRepository.keys.toList()
                val (qa, qb) = withContext(Dispatchers.Default) {
                    KeyMatcher.preload(keys)
                    Pair(KeyMatcher.extractFile(a), b?.let { KeyMatcher.extractFile(it) })
                }
                if (qa == null) {
                    tvStatus.text = "Aufnahme konnte nicht gelesen werden."
                    pb.visibility = View.GONE
                    return@launch
                }
                pb.isIndeterminate = false
                val results = KeyMatcher.identify(qa, qb, keys) { done, total ->
                    runOnUiThread {
                        pb.max = total
                        pb.progress = done
                        tvStatus.text = "Vergleiche $done von $total Schlüsseln…"
                    }
                }
                showResults(results, if (qb != null) 2 else 1)
            } catch (e: Exception) {
                tvStatus.text = "Fehler bei der Erkennung: ${e.message}"
                pb.visibility = View.GONE
            }
        }
    }

    private fun showResults(results: List<KeyMatcher.MatchResult>, sides: Int) {
        pb.visibility = View.GONE
        tvStatus.visibility = View.GONE
        card.visibility = View.VISIBLE

        val best = results.firstOrNull()
        val level = KeyMatcher.verdict(results, sides)
        val sideText = if (sides == 2) "beide Seiten" else "nur eine Seite"
        when (level) {
            KeyMatcher.Level.SURE -> {
                card.setCardBackgroundColor(0xFF2E7D32.toInt())
                tvVerdict.text = "✓  ${best?.key?.name}"
                tvVerdictDetail.text = listOfNotNull(
                    best?.key?.subtitle()?.takeIf { it.isNotBlank() },
                    "Eindeutiger Treffer ($sideText verglichen)"
                ).joinToString("\n")
            }
            KeyMatcher.Level.LIKELY -> {
                card.setCardBackgroundColor(0xFFEF6C00.toInt())
                tvVerdict.text = "?  Wahrscheinlich: ${best?.key?.name}"
                tvVerdictDetail.text = "Nicht eindeutig – bitte mit den Kandidaten unten abgleichen. " +
                    if (sides == 1) "Mit beiden Seiten wird das Ergebnis sicherer." else "Ggf. neu aufnehmen (Licht, Schärfe)."
            }
            KeyMatcher.Level.NONE -> {
                card.setCardBackgroundColor(0xFF616161.toInt())
                tvVerdict.text = "✗  Kein passender Schlüssel"
                tvVerdictDetail.text = "Der Schlüssel ist vermutlich nicht eingelernt – oder die Aufnahme ist unscharf bzw. spiegelt."
            }
        }
        adapter.submit(results.take(5).filter { it.score > 0 })
    }

    /** Aufnahme dem gewählten Schlüssel als weiteres Lernfoto hinzufügen. */
    private fun confirm(r: KeyMatcher.MatchResult) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Das ist „${r.key.name}“?")
            .setMessage("Die Aufnahme wird diesem Schlüssel als zusätzliches Foto hinzugefügt. Das verbessert künftige Erkennungen.")
            .setPositiveButton("Ja, hinzufügen") { _, _ -> addToKey(r) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun addToKey(r: KeyMatcher.MatchResult) {
        val a = fileA ?: return
        val b = fileB
        val sideOfA = if (r.crossed) SIDE_B else SIDE_A
        val sideOfB = if (r.crossed) SIDE_A else SIDE_B
        val pd = progressDialog("Speichere…")
        lifecycleScope.launch {
            try {
                val imgs = withContext(Dispatchers.Default) {
                    listOfNotNull(
                        KeyRepository.importPhoto(r.key.id, a, sideOfA),
                        b?.let { KeyRepository.importPhoto(r.key.id, it, sideOfB) }
                    )
                }
                r.key.images.addAll(imgs)
                KeyRepository.save()
                toast("Gespeichert – danke, die Erkennung wird dadurch besser.")
            } catch (e: Exception) {
                toast("Fehler: ${e.message}")
            } finally {
                pd.dismiss()
            }
        }
    }

    private fun deleteQueries() {
        fileA?.delete()
        fileB?.delete()
    }

    override fun onDestroy() {
        if (isFinishing) deleteQueries()
        super.onDestroy()
    }
}
