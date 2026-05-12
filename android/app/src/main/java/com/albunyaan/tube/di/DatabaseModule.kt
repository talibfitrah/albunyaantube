package com.albunyaan.tube.di

import android.content.Context
import androidx.room.Room
import com.albunyaan.tube.BuildConfig
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.ChannelFeedRefreshStateDao
import com.albunyaan.tube.data.local.ChannelVideoCacheDao
import com.albunyaan.tube.data.local.FavoriteVideoDao
import com.albunyaan.tube.data.local.FavoritesRepository
import com.albunyaan.tube.data.local.FavoritesRepositoryImpl
import com.albunyaan.tube.data.local.FollowedChannelDao
import com.albunyaan.tube.data.local.FollowedChannelsRepository
import com.albunyaan.tube.data.local.FollowedChannelsRepositoryImpl
import com.albunyaan.tube.data.local.MIGRATION_1_2
import com.albunyaan.tube.data.local.MIGRATION_2_3
import com.albunyaan.tube.data.local.MIGRATION_3_4
import com.albunyaan.tube.data.local.MIGRATION_4_5
import com.albunyaan.tube.data.local.MIGRATION_5_6
import com.albunyaan.tube.data.local.AccountBindingDao
import com.albunyaan.tube.data.local.MIGRATION_6_7
import com.albunyaan.tube.data.local.MIGRATION_7_8
import com.albunyaan.tube.data.local.SyncStateDao
import com.albunyaan.tube.data.local.SavedPlaylistDao
import com.albunyaan.tube.data.local.SubscribedChannelDao
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
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                MIGRATION_7_8,
            )

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
    fun provideSubscribedChannelDao(database: AppDatabase): SubscribedChannelDao =
        database.subscribedChannelDao()

    @Provides
    @Singleton
    fun provideSavedPlaylistDao(database: AppDatabase): SavedPlaylistDao =
        database.savedPlaylistDao()

    @Provides
    @Singleton
    fun provideChannelVideoCacheDao(database: AppDatabase): ChannelVideoCacheDao =
        database.channelVideoCacheDao()

    @Provides
    @Singleton
    fun provideChannelFeedRefreshStateDao(database: AppDatabase): ChannelFeedRefreshStateDao =
        database.channelFeedRefreshStateDao()

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

    @Provides
    @Singleton
    fun provideSyncStateDao(database: AppDatabase): SyncStateDao = database.syncStateDao()

    @Provides
    @Singleton
    fun provideAccountBindingDao(database: AppDatabase): AccountBindingDao = database.accountBindingDao()
}
