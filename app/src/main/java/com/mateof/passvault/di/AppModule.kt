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
            // No destructive fallback. A wallet is the only copy of tickets somebody paid for, and
            // wiping it on a schema change would be the worst possible way to handle an upgrade —
            // a missing migration should stop the build, not delete the user's data.
            .build()

    @Provides
    fun walletDao(database: PassVaultDatabase): WalletDao = database.walletDao()

    @Provides
    @Singleton
    fun deviceKeys(@ApplicationContext context: Context): DeviceKeys = KeyStoreDeviceKeys(context)

    @Provides
    @Singleton
    fun walletRepository(dao: WalletDao, keys: DeviceKeys): WalletRepository =
        WalletRepository(dao, keys)
}
