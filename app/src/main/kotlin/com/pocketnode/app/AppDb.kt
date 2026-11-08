package com.pocketnode.app

import android.content.Context

/** The one database connection the app shares, so the assistant, the reminder alarm and the screens never fight over the file. */
object AppDb {
    @Volatile private var db: AndroidSqlDb? = null

    fun get(context: Context): AndroidSqlDb = db ?: synchronized(this) { db ?: AndroidSqlDb(context.applicationContext).also { db = it } }
}
