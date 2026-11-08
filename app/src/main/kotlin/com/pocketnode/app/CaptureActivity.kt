package com.pocketnode.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.CompletableFuture

/** Hands a photo request to the phone's camera app and reports back whether a photo was taken. Has no screen of its own. */
class CaptureActivity : ComponentActivity() {
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        CameraCapture.finish(taken)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = CameraCapture.uri
        if (uri == null) { CameraCapture.finish(false); finish(); return }
        if (savedInstanceState == null) {
            try { takePicture.launch(uri) } catch (e: Exception) { CameraCapture.finish(false); finish() }
        }
    }

    override fun onDestroy() {
        // Closed by the system or the owner before an answer: do not leave the assistant waiting.
        if (isFinishing) CameraCapture.finish(false)
        super.onDestroy()
    }
}

/** One photo request at a time, shared between the assistant's thread and [CaptureActivity]. */
object CameraCapture {
    @Volatile var uri: Uri? = null
    @Volatile private var waiting: CompletableFuture<Boolean>? = null

    @Synchronized
    fun begin(target: Uri): CompletableFuture<Boolean> {
        check(waiting == null) { "The camera is already being used." }
        uri = target
        return CompletableFuture<Boolean>().also { waiting = it }
    }

    @Synchronized
    fun finish(taken: Boolean) {
        waiting?.complete(taken)
        waiting = null
        uri = null
    }
}
