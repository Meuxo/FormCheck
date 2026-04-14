package com.meuxo.formcheck.di

import android.content.Context
import androidx.room.Room
import com.meuxo.formcheck.data.local.FormCheckDatabase
import com.meuxo.formcheck.data.local.SessionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FormCheckDatabase =
        Room.databaseBuilder(
            context,
            FormCheckDatabase::class.java,
            "formcheck.db"
        ).build()

    @Provides
    fun provideSessionDao(db: FormCheckDatabase): SessionDao = db.sessionDao()
}
