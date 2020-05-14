package io.libp2p.example.chat

import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.CompletableFuture.runAsync


class MainActivity : AppCompatActivity() {
    private lateinit var chatScroller: ScrollView
    private lateinit var chatWindow: TextView
    private lateinit var line: EditText
    private lateinit var sendButton: Button
    private lateinit var chatNode: ChatNode
    private lateinit var multicastLock: WifiManager.MulticastLock

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        chatScroller = findViewById(R.id.chatScroll)
        chatWindow = findViewById(R.id.chat)
        line = findViewById(R.id.line)
        sendButton = findViewById(R.id.send)

        sendButton.setOnClickListener { sendText() }

        runAsync {
            acquireMulticastLock()

            chatNode = ChatNode(::chatMessage)
            chatMessage("\nLibp2p Chatter!\n=============\n")
            chatMessage("This node is ${chatNode.peerId}, listening on ${chatNode.address}\n")
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        releaseMulticastLock()
        chatNode.stop()
    } // onDestroy

    private fun sendText() {
        val msg = line.text.toString().trim()
        if (msg.isEmpty())
            return

        // send message here
        chatNode.send(msg)

        chatMessage("You > " + msg)

        line.text.clear()
    } // sendText

    private fun chatMessage(msg: String) {
        runOnUiThread {
            chatWindow.append(msg)
            chatWindow.append("\n")
            chatScroller.post { chatScroller.fullScroll(View.FOCUS_DOWN) }
        }
    } // chatMessage

    private fun acquireMulticastLock() {
        val wifi = getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("libp2p-chatter")
        multicastLock.acquire()
    }

    private fun releaseMulticastLock() {
       multicastLock.release()
    }
}
