package de.ric.schluessel

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Activity.progressDialog(msg: String): AlertDialog {
    val pad = dp(24)
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(pad, pad, pad, pad)
        addView(ProgressBar(context))
        addView(TextView(context).apply {
            text = msg
            setPadding(pad, 0, 0, 0)
        })
    }
    return MaterialAlertDialogBuilder(this).setView(layout).setCancelable(false).show()
}

/** Bindet die Eingabefelder aus view_key_fields.xml. */
class KeyFields(root: View) {
    private val lName: TextInputLayout = root.findViewById(R.id.lName)
    private val name: TextInputEditText = root.findViewById(R.id.etName)
    private val number: TextInputEditText = root.findViewById(R.id.etNumber)
    private val location: TextInputEditText = root.findViewById(R.id.etLocation)
    private val note: TextInputEditText = root.findViewById(R.id.etNote)

    fun fill(k: KeyEntry) {
        name.setText(k.name)
        number.setText(k.number)
        location.setText(k.location)
        note.setText(k.note)
    }

    fun validate(): Boolean {
        val ok = !name.text.isNullOrBlank()
        lName.error = if (ok) null else "Bitte eine Bezeichnung eingeben"
        return ok
    }

    fun applyTo(k: KeyEntry) {
        k.name = name.text.toString().trim()
        k.number = number.text.toString().trim()
        k.location = location.text.toString().trim()
        k.note = note.text.toString().trim()
    }
}
