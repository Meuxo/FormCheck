package com.meuxo.formcheck.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SessionEntity::class],
    version = 1,
    exportSchema = true
)
abstract class FormCheckDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
}
