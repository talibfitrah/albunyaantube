package com.albunyaan.tube.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.albunyaan.tube.BuildConfig
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.FavoriteVideoDao
import com.albunyaan.tube.data.local.FavoritesRepository
import com.albunyaan.tube.data.local.FavoritesRepositoryImpl
import com.albunyaan.tube.data.local.FollowedChannelDao
import com.albunyaan.tube.data.local.FollowedChannelsRepository
import com.albunyaan.tube.data.local.FollowedChannelsRepositoryImpl
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
            // Register real migrations so release builds can upgrade an
            // existing install without data loss. A missing migration here
            // causes an IllegalStateException on first launch after upgrade.
            .addMigrations(AppDatabase.MIGRATION_1_2)

        // SAFETY: Only allow destructive migration in debug builds as a
        // developer convenience when schemas are in flux. Release builds
        // MUST rely on the registered migrations above — never destructive.
        if (BuildConfig.DEBUG) {
            builder.fallbackToDestructiveMigration(dropAllTables = true)
        }

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

    @Provides
    @Singleton
    fun provideFollowedChannelDao(database: AppDatabase): FollowedChannelDao {
        return database.followedChannelDao()
    }

    @Provides
    @Singleton
    fun provideFollowedChannelsRepository(
        followedChannelDao: FollowedChannelDao
    ): FollowedChannelsRepository {
        return FollowedChannelsRepositoryImpl(followedChannelDao)
    }
}
