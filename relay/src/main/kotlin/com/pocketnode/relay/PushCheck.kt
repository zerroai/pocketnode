package com.pocketnode.relay

import java.io.File

/** Developer check: sends one wake-up through a service account. Usage: PushCheck <service-account.json> <device-token> */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: PushCheck <service-account.json> <device-token>" }
    val result = FcmPusher(File(args[0]).readText()).wake(Device("check", "fcm", args[1]))
    println("push result: $result")
}
