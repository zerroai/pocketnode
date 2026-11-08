package com.pocketnode.core

/**
 * One thing a pack may ask the phone to do. [sensitive] operations (private data, location, camera) are never granted by
 * default: the owner switches each on, and Android asks for its own permission too. [foreground] operations open another
 * screen (or need the app showing, like a location fix), which Android only allows while Pocketnode is on screen.
 * [askEachTime] operations hand private content to the assistant (and so to the model provider): even when switched on, the
 * owner is asked every time and can say no, and with the app closed they cannot be asked, so the answer is no.
 */
data class DeviceOp(val name: String, val label: String, val sensitive: Boolean = false, val foreground: Boolean = false, val askEachTime: Boolean = false)

/** The phone abilities that exist. A pack lists the ones it uses in its manifest as `"device": ["apps.open", ...]`. */
object DeviceOps {
    val all: List<DeviceOp> = listOf(
        DeviceOp("status", "See the phone's battery, network and storage"),
        DeviceOp("apps.list", "See which apps are installed", sensitive = true, askEachTime = true),
        DeviceOp("apps.open", "Open apps", foreground = true),
        DeviceOp("flashlight", "Turn the flashlight on and off"),
        DeviceOp("media.control", "Play, pause and skip music or video in other apps, and change the volume"),
        DeviceOp("alarm.set", "Set alarms", foreground = true),
        DeviceOp("timer.set", "Start timers", foreground = true),
        DeviceOp("dial", "Start a phone call (opens the dialer, you press call)", foreground = true),
        DeviceOp("sms.compose", "Start a text message (opens it for you to send)", foreground = true),
        DeviceOp("email.compose", "Start an email (opens it for you to send)", foreground = true),
        DeviceOp("share.text", "Send text to another app, such as a notes app", foreground = true),
        DeviceOp("url.open", "Open web links", foreground = true),
        DeviceOp("map.open", "Open places in maps", foreground = true),
        DeviceOp("notify", "Show notifications"),
        DeviceOp("speak", "Speak out loud"),
        DeviceOp("clipboard.set", "Copy text to the clipboard"),
        DeviceOp("reminder.add", "Set reminders that notify you later"),
        DeviceOp("reminder.list", "See the reminders it set"),
        DeviceOp("reminder.cancel", "Cancel reminders"),
        DeviceOp("mail.send", "Send email from your account (you approve each one)", sensitive = true, askEachTime = true),
        DeviceOp("mail.inbox", "See who your latest emails are from", sensitive = true, askEachTime = true),
        DeviceOp("mail.read", "Read emails you choose", sensitive = true, askEachTime = true),
        DeviceOp("pdf.create", "Make PDF documents"),
        DeviceOp("owner.confirm", "Ask you before it deletes or cancels something"),
        DeviceOp("sheet.create", "Make Excel spreadsheets"),
        DeviceOp("docx.create", "Make Word documents"),
        DeviceOp("slides.create", "Make PowerPoint presentations"),
        DeviceOp("file.create", "Make text and CSV files"),
        DeviceOp("file.share", "Share files it made (opens the share sheet)", foreground = true),
        DeviceOp("attachment.read", "Read the text files you attach to a message"),
        DeviceOp("calendar.add", "Start a calendar event (opens it for you to save)", foreground = true),
        DeviceOp("calendar.events", "Read your calendar", sensitive = true, askEachTime = true),
        DeviceOp("contacts.search", "Read your contacts", sensitive = true, askEachTime = true),
        DeviceOp("contacts.add", "Start a new contact (opens it for you to save)", foreground = true),
        DeviceOp("location.get", "Know where this phone is", sensitive = true, foreground = true, askEachTime = true),
        DeviceOp("camera.snap", "Take photos and show them to your AI model", sensitive = true, foreground = true),
        DeviceOp("photos.latest", "Look at your latest photos and show them to your AI model", sensitive = true, askEachTime = true),
    )

    private val byName = all.associateBy { it.name }
    fun get(name: String): DeviceOp? = byName[name]
    fun isSensitive(name: String) = byName[name]?.sensitive ?: true // an unknown operation is treated as the most careful kind
}

/** What the phone does when a pack asks. The app provides the real one. Blocking; may take a while (for example a location fix). */
fun interface DeviceHost {
    fun call(op: String, argsJson: String): String

    companion object {
        val None = DeviceHost { _, _ -> throw IllegalStateException("This phone feature is not available here.") }
    }
}
