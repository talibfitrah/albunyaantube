package com.albunyaan.tube.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.albunyaan.tube.BuildConfig
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.FavoriteVideoDao
import com.albunyaan.tube.data.local.FavoritesRepository
import com.albunyaan.tube.data.local.FavoritesRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing Room database dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        val builder = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )

        // SAFETY: Only allow destructive migration in debug builds.
        // Release builds will crash on schema mismatch, forcing proper migration implementation.
        // This prevents silent data loss in production.
        if (BuildConfig.DEBUG) {
            builder.fallbackToDestructiveMigration()
        }
        // TODO: Before first production release, implement proper Room migrations
        // to handle schema changes without losing user favorites data.

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideFavoriteVideoDao(database: AppDatabase): FavoriteVideoDao {
        return database.favoriteVideoDao()
    }

    @Provides
    @Singleton
    fun provideFavoritesRepository(
        favoriteVideoDao: FavoriteVideoDao
    ): FavoritesRepository {
        return FavoritesRepositoryImpl(favoriteVideoDao)
    }
}
