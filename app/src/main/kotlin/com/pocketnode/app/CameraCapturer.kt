package com.pocketnode.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Opens the phone's camera app and waits for the owner to take a photo. Used when the assistant asks for a photo and when the
 * owner taps the camera button in a chat. The photo is shrunk and kept by [PhotoFiles]; nothing is taken without the owner
 * pressing the shutter.
 */
class CameraCapturer(private val app: Context, private val photos: PhotoFiles) {
    /** Blocks until a photo is taken or the camera is closed. Throws with a plain message if no photo came back. */
    fun capture(waitSeconds: Long = 150): PhotoFiles.Stored {
        photos.tidy()
        val dir = File(app.cacheDir, "capture").apply { mkdirs() }
        val file = File(dir, "shot-${System.nanoTime()}.jpg")
        val uri = FileProvider.getUriForFile(app, app.packageName + ".files", file)
        val waiting = try { CameraCapture.begin(uri) } catch (e: IllegalStateException) { throw IllegalStateException("The camera is already open.") }
        try {
            try { app.startActivity(Intent(app, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: android.content.ActivityNotFoundException) { CameraCapture.finish(false); throw IllegalStateException("This phone has no camera app.") }
            val taken = try { waiting.get(waitSeconds, TimeUnit.SECONDS) } catch (e: TimeoutException) { CameraCapture.finish(false); false }
            if (!taken || !file.isFile || file.length() == 0L) throw IllegalStateException(NO_PHOTO)
            return photos.store(file.readBytes())
        } finally {
            file.delete()
        }
    }

    companion object {
        const val NO_PHOTO = "No photo was taken: the camera was closed without a picture. Offer to try again."
    }
}
