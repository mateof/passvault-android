package com.mateof.passvault.di

import android.content.Context
import androidx.room.Room
import com.mateof.passvault.data.DeviceKeys
import com.mateof.passvault.data.KeyStoreDeviceKeys
import com.mateof.passvault.data.PassVaultDatabase
import com.mateof.passvault.data.WalletDao
import com.mateof.passvault.data.WalletRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): PassVaultDatabase =
        Room.databaseBuilder(context, PassVaultDatabase::class.java, "passvault.db")
            .addMigrations(
                com.mateof.passvault.data.MIGRATION_1_2,
                com.mateof.passvault.data.MIGRATION_2_3,
            )
            // No destructive fallback. A wallet is the only copy of tickets somebody paid for, and
            // wiping it on a schema change would be the worst possible way to handle an upgrade —
            // a missing migration should stop the build, not delete the user's data.
            .build()

    @Provides
    fun walletDao(database: PassVaultDatabase): WalletDao = database.walletDao()

    @Provides
    fun operationDao(database: PassVaultDatabase): com.mateof.passvault.data.OperationDao =
        database.operationDao()

    @Provides
    fun documentDao(database: PassVaultDatabase): com.mateof.passvault.data.DocumentDao =
        database.documentDao()

    @Provides
    @Singleton
    fun documentStore(
        @ApplicationContext context: Context,
        keys: DeviceKeys,
    ): com.mateof.passvault.data.DocumentStore =
        com.mateof.passvault.data.DocumentStore(context, keys)

    @Provides
    @Singleton
    fun deviceKeys(@ApplicationContext context: Context): DeviceKeys = KeyStoreDeviceKeys(context)

    @Provides
    @Singleton
    fun rasterizer(@ApplicationContext context: Context): com.mateof.passvault.ingest.PageRasterizer =
        com.mateof.passvault.ingest.AndroidRasterizer(context)

    @Provides
    @Singleton
    fun walletRepository(
        dao: WalletDao,
        keys: DeviceKeys,
        log: com.mateof.passvault.sync.OperationLog,
        documents: com.mateof.passvault.data.DocumentDao,
        store: com.mateof.passvault.data.DocumentStore,
    ): WalletRepository = WalletRepository(dao, keys, log, documents, store)

    @Provides
    @Singleton
    fun operationLog(
        dao: com.mateof.passvault.data.OperationDao,
        keys: DeviceKeys,
    ): com.mateof.passvault.sync.OperationLog =
        com.mateof.passvault.sync.OperationLog(dao, keys)
}
