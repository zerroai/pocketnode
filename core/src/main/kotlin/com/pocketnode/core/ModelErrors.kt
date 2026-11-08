package com.pocketnode.core

import java.io.IOException
import java.net.SocketTimeoutException

/** Turns what a failed model call throws into a sentence the owner can act on. */
object ModelErrors {
    /** The sentence stored when the phone could not reach the model at all. */
    const val NO_CONNECTION = "Could not reach the model. Check your connection and try again."


    fun friendly(e: Throwable): String = when {
        e is ModelHttpException -> when (e.status) {
            401, 403 -> "The provider did not accept your API key. Check it in Settings."
            402 -> "The provider says your account is out of credit."
            404 -> "The provider does not know this model. Check the model name in Settings."
            408, 504 -> "The provider took too long to answer. Try again."
            429 -> "The provider is limiting your requests, or your credit has run out. Try again shortly."
            in 500..599 -> "The provider had a problem on its side. Try again in a moment."
            else -> e.message.orEmpty()
        }
        e is SocketTimeoutException -> "The model took too long to answer. Try again."
        e is IOException -> NO_CONNECTION
        else -> e.message.orEmpty().ifBlank { "Something went wrong." }
    }
}
