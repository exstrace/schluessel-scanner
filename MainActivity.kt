package de.ric.schluessel

import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: KeyAdapter
    private lateinit var etSearch: EditText
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView

    private val enrollLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            val a = res.data?.getStringExtra(CaptureActivity.EXTRA_A) ?: return@registerForActivityResult
            showEnrollForm(File(a), res.data?.getStringExtra(CaptureActivity.EXTRA_B)?.let { File(it) })
        }
    }

    private val identifyLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            val a = res.data?.getStringExtra(CaptureActivity.EXTRA_A) ?: return@registerForActivityResult
            startActivity(ResultActivity.intent(this, a, res.data?.getStringExtra(CaptureActivity.EXTRA_B)))
        }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { doExport(it) }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { doImport(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        KeyRepository.init(this)

        if (!KeyMatcher.init()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Bildverarbeitung nicht verfügbar")
                .setMessage("OpenCV konnte auf diesem Gerät nicht geladen werden.")
                .setPositiveButton("OK", null)
                .show()
        }

        etSearch = findViewById(R.id.etSearch)
        tvCount = findViewById(R.id.tvCount)
        tvEmpty = findViewById(R.id.tvEmpty)

        adapter = KeyAdapter { startActivity(DetailActivity.intent(this, it.id)) }
        findViewById<RecyclerView>(R.id.rvKeys).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        etSearch.doAfterTextChanged { refresh() }

        findViewById<MaterialButton>(R.id.btnIdentify).setOnClickListener {
            if (KeyRepository.keys.isEmpty()) {
                toast("Es sind noch keine Schlüssel eingelernt.")
            } else {
                identifyLauncher.launch(CaptureActivity.intent(this, CaptureActivity.MODE_IDENTIFY))
            }
        }
        findViewById<MaterialButton>(R.id.btnEnroll).setOnClickListener {
            enrollLauncher.launch(CaptureActivity.intent(this, CaptureActivity.MODE_ENROLL))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val q = etSearch.text.toString().trim().lowercase()
        val list = KeyRepository.keys
            .filter { q.isEmpty() || "${it.name} ${it.number} ${it.location} ${it.note}".lowercase().contains(q) }
            .sortedBy { it.name.lowercase() }
        adapter.submit(list)
        val total = KeyRepository.keys.size
        tvCount.text = if (total == 1) "1 Schlüssel gespeichert" else "$total Schlüssel gespeichert"
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        tvEmpty.text = if (total == 0) "Noch keine Schlüssel.\nTippe auf „Neuen Schlüssel einlernen“." else "Kein Treffer für die Suche."
    }

    // ---------------------------------------------------------------- Einlernen

    private fun showEnrollForm(a: File, b: File?) {
        val view = layoutInflater.inflate(R.layout.view_key_fields, null)
        val fields = KeyFields(view)
        val pad = dp(20)
        view.setPadding(pad, dp(8), pad, 0)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (b == null) "Schlüssel speichern (nur Seite A)" else "Schlüssel speichern")
            .setView(view)
            .setPositiveButton("Speichern", null)
            .setNegativeButton("Verwerfen") { _, _ -> a.delete(); b?.delete() }
            .setCancelable(false)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!fields.validate()) return@setOnClickListener
                dialog.dismiss()
                val entry = KeyEntry(KeyRepository.newId(), "", "", "", "", System.currentTimeMillis())
                fields.applyTo(entry)
                saveNewKey(entry, a, b)
            }
        }
        dialog.show()
    }

    private fun saveNewKey(entry: KeyEntry, a: File, b: File?) {
        val pd = progressDialog("Merkmale werden berechnet…")
        lifecycleScope.launch {
            try {
                val imgs = withContext(Dispatchers.Default) {
                    listOfNotNull(
                        KeyRepository.importPhoto(entry.id, a, SIDE_A),
                        b?.let { KeyRepository.importPhoto(entry.id, it, SIDE_B) }
                    )
                }
                entry.images.addAll(imgs)
                KeyRepository.add(entry)
                toast("„${entry.name}“ gespeichert. Tipp: 2–3 Fotos pro Seite verbessern die Erkennung.")
            } catch (e: Exception) {
                toast("Fehler beim Speichern: ${e.message}")
            } finally {
                a.delete(); b?.delete()
                pd.dismiss()
                refresh()
            }
        }
    }

    // ---------------------------------------------------------------- Menü

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Datenbank exportieren")
        menu.add(0, 2, 1, "Datenbank importieren")
        menu.add(0, 3, 2, "Tipps für gute Fotos")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date())
                exportLauncher.launch("schluessel_$date.zip")
            }
            2 -> importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            3 -> showHelp()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun doExport(uri: Uri) {
        val pd = progressDialog("Exportiere…")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { KeyRepository.export(this@MainActivity, uri) }
                toast("Export abgeschlossen (${KeyRepository.keys.size} Schlüssel).")
            } catch (e: Exception) {
                toast("Export fehlgeschlagen: ${e.message}")
            } finally {
                pd.dismiss()
            }
        }
    }

    private fun doImport(uri: Uri) {
        val pd = progressDialog("Importiere…")
        lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { KeyRepository.import(this@MainActivity, uri) }
                toast("Import: ${r.added} neu, ${r.updated} aktualisiert.")
            } catch (e: Exception) {
                toast("Import fehlgeschlagen: ${e.message}")
            } finally {
                pd.dismiss()
                refresh()
            }
        }
    }

    private fun showHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Tipps für gute Erkennung")
            .setMessage(
                "• Schlüssel flach auf einen ruhigen, einfarbigen, matten Untergrund legen (z. B. weißes Papier).\n\n" +
                "• Kamera parallel zum Schlüssel halten, ca. 10–15 cm Abstand. Zum Scharfstellen auf den Bart tippen.\n\n" +
                "• Bart vollständig in den Rahmen, Schlüssel waagerecht. Ob Spitze links oder rechts zeigt, ist egal.\n\n" +
                "• Spiegelungen vermeiden: notfalls „Licht an“ oder leicht schräges Tageslicht.\n\n" +
                "• Beim Einlernen 2–3 Fotos pro Seite machen (in der Detailansicht „Fotos ergänzen“).\n\n" +
                "• Nach einer Erkennung „Das ist er“ tippen – dann lernt die App mit.\n\n" +
                "• Baugleiche Schlüssel derselben Schließung sehen für die Kamera identisch aus und werden nicht unterschieden."
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
