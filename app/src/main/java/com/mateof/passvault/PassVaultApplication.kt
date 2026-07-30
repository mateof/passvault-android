package com.mateof.passvault

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp

/**
 * PdfBox-Android loads its font resources from the asset manager, and it has to be
 * told where they are before any document is opened. Doing it here rather than
 * lazily in the ingestion code means the first import is not the one that fails.
 */
@HiltAndroidApp
class PassVaultApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
    }
}
