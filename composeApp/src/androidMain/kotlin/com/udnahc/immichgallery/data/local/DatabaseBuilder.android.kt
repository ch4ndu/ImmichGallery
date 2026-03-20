package com.udnahc.immichgallery.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

fun getRoomDatabase(context: Context): AppDatabase {
    val dbFile = context.getDatabasePath("immich_gallery.db")
    return Room.databaseBuilder<AppDatabase>(context = context, name = dbFile.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(true)
        .build()
}
