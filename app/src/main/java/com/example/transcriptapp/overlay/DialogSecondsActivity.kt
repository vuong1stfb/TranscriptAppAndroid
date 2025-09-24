package com.example.transcriptapp.overlay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Window
import android.widget.Button
import android.widget.EditText
import com.example.transcriptapp.R

class DialogSecondsActivity : Activity() {
    companion object {
        const val EXTRA_SECONDS = "extra_seconds"
        const val ACTION_SET_SPLIT_SECONDS = "com.example.transcriptapp.ACTION_SET_SPLIT_SECONDS"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Use dialog theme set in manifest/style
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_dialog_seconds)

        val edit = findViewById<EditText>(R.id.dialogEditSeconds)
        edit.inputType = InputType.TYPE_CLASS_NUMBER

        val btnOk = findViewById<Button>(R.id.dialogBtnOk)
        val btnCancel = findViewById<Button>(R.id.dialogBtnCancel)

        btnOk.setOnClickListener {
            val secs = edit.text.toString().toIntOrNull() ?: 0
            val intent = Intent(ACTION_SET_SPLIT_SECONDS).apply {
                putExtra(EXTRA_SECONDS, secs)
            }
            sendBroadcast(intent)
            finish()
        }

        btnCancel.setOnClickListener {
            finish()
        }
    }
}
