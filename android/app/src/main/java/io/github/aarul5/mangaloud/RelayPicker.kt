package io.github.aarul5.mangaloud

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast

object RelayPicker {
    /** Quick switch between saved relays. Takes effect on the next Read. */
    fun pick(activity: Activity, settings: Settings, onPicked: (Relay) -> Unit) {
        val relays = settings.relays
        if (relays.size < 2) {
            Toast.makeText(activity, "Add another relay in Settings > PC relay first", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = relays.map { "${it.label}  (${it.url})" }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Switch relay")
            .setSingleChoiceItems(labels, settings.activeRelayIndex.coerceIn(0, relays.lastIndex)) { dialog, which ->
                settings.ocrMode = OcrMode.PC_RELAY
                settings.activeRelayIndex = which
                onPicked(relays[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
