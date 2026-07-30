package com.mateof.passvault.ingest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Turning documents into pixels, using the platform.
 *
 * `PdfRenderer` rather than PdfBox's own renderer: it is hardware-accelerated, ships with the
 * system, and adds nothing to the download. PdfBox is still here for splitting a document into
 * per-page files, which `PdfRenderer` cannot do.
 *
 * `PdfRenderer` insists on a seekable file descriptor, so the bytes are written to the cache first.
 * The file is deleted immediately afterwards — a ticket document left in a cache directory is a
 * plaintext barcode sitting outside the encrypted store, which is exactly what the rest of this app
 * goes to some trouble to avoid.
 */
class AndroidRasterizer(private val context: Context) : PageRasterizer {

    override fun pageCount(pdf: ByteArray): Int = withRenderer(pdf) { renderer -> renderer.pageCount }

    override fun render(pdf: ByteArray, pageNumber: Int, widthPx: Int): RasterPage {
        // An image is decoded directly; only a PDF needs the renderer.
        if (detectMediaKind(pdf) != MediaKind.PDF) {
            return decodeImage(pdf, pageNumber)
        }
        return withRenderer(pdf) { renderer ->
            renderer.openPage(pageNumber - 1).use { page ->
                val scale = widthPx.toFloat() / page.width
                val width = widthPx
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                    // White first: a transparent background flattens to black, and a barcode on
                    // black does not decode.
                    eraseColor(Color.WHITE)
                }
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.toRasterPage(pageNumber)
            }
        }
    }

    private fun decodeImage(bytes: ByteArray, pageNumber: Int): RasterPage {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IngestException(IngestError.DAMAGED_FILE, "image could not be decoded")
        return bitmap.toRasterPage(pageNumber)
    }

    private fun Bitmap.toRasterPage(pageNumber: Int): RasterPage {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val page = RasterPage(pageNumber, width, height, pixels)
        recycle()
        return page
    }

    private fun <T> withRenderer(pdf: ByteArray, block: (PdfRenderer) -> T): T {
        val file = File.createTempFile("ingest", ".pdf", context.cacheDir)
        return try {
            file.writeBytes(pdf)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use(block)
            }
        } catch (cause: Exception) {
            if (cause is IngestException) throw cause
            throw IngestException(IngestError.DAMAGED_FILE, "PDF could not be opened", cause)
        } finally {
            // Not left for the system to clear later: while it exists it is an unencrypted copy of
            // somebody's ticket.
            file.delete()
        }
    }
}
