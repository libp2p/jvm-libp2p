package io.libp2p.example.chatter

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

class MainActivity : AppCompatActivity() {
    private lateinit var chatWindow: TextView
    private lateinit var line: EditText
    private lateinit var sendButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        chatWindow = findViewById(R.id.chat)
        line = findViewById(R.id.line)
        sendButton = findViewById(R.id.send)

        sendButton.setOnClickListener { sendText() }
    }

    private fun sendText() {
        val msg = line.text.trim()
        if (msg.isEmpty())
            return

        // send message here
        chatWindow.append(msg)
        chatWindow.append("\n")

        line.text.clear()
    }
}
