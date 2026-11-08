package com.pocketnode.app

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives the relay's "wake up" push. The push carries no message text: it only makes the phone
 * connect to the relay and collect what is waiting.
 */
class WakeService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        CoreHost.init(this)
        CoreHost.onPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "wake") return
        Log.i("Pocketnode", "wake-up received")
        CoreHost.init(this)
        CoreService.start(this) // a high-priority push is allowed to start the foreground service
        CoreHost.wakeFromPush()
    }
}
