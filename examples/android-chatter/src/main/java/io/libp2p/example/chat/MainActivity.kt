package io.libp2p.example.chat

import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.CompletableFuture.runAsync


class MainActivity : AppCompatActivity() {
    private lateinit var chatWindow: TextView
    private lateinit var line: EditText
    private lateinit var sendButton: Button
    private lateinit var chatNode: ChatNode
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        chatWindow = findViewById(R.id.chat)
        line = findViewById(R.id.line)
        sendButton = findViewById(R.id.send)

        sendButton.setOnClickListener { sendText() }

        val wifi = getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("multicastLock")
        multicastLock?.acquire()

        runAsync {
            chatNode = ChatNode(::chatMessage)
            chatMessage("\nLibp2p Chatter!\n=============\n")
            chatMessage("This node is ${chatNode.peerId}, listening on ${chatNode.address}\n")
        }
    }

    override fun onDestroy() {
        multicastLock?.release()
        multicastLock = null
        super.onDestroy()
    }

    private fun sendText() {
        val msg = line.text.toString().trim()
        if (msg.isEmpty())
            return

        // send message here

        chatMessage(msg)

        line.text.clear()
    }

    private fun chatMessage(msg: String) {
        chatWindow.append(msg)
        chatWindow.append("\n")
    }
}
