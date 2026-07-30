package com.mateof.passvault.ingest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reading a PDF on a real device.
 *
 * The part that cannot be a JVM test: `PdfRenderer` and `PdfDocument` are platform classes with no
 * desktop equivalent. Everything downstream of the pixels is covered by `IngestTest`; this proves
 * the pixels themselves come out of a real PDF in a state a decoder can read.
 *
 * The PDF is built here with `PdfDocument` and carries barcodes drawn by ZXing, so nothing about
 * the chain is stubbed: encode, draw, write a PDF, render it back, decode.
 */
@RunWith(AndroidJUnit4::class)
class AndroidRasterizerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val rasterizer = AndroidRasterizer(context)

    private fun barcodeBitmap(payload: String, format: BarcodeFormat, size: Int = 500): Bitmap {
        val matrix = MultiFormatWriter().encode(payload, format, size, size)
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                pixels[y * matrix.width + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }

    /** A real PDF, one page per list entry, each carrying the payloads given. */
    private fun pdfWith(pages: List<List<String>>): ByteArray {
        val document = PdfDocument()
        pages.forEachIndexed { index, payloads ->
            val info = PdfDocument.PageInfo.Builder(595, 842, index + 1).create()
            val page = document.startPage(info)
            val canvas: Canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            payloads.forEachIndexed { ordinal, payload ->
                val bitmap = barcodeBitmap(payload, BarcodeFormat.QR_CODE, 260)
                canvas.drawBitmap(bitmap, 40f + ordinal * 280f, 120f, null)
                bitmap.recycle()
            }
            document.finishPage(page)
        }
        val out = ByteArrayOutputStream()
        document.writeTo(out)
        document.close()
        return out.toByteArray()
    }

    @Test
    fun countsThePagesOfARealPdf() {
        val pdf = pdfWith(listOf(listOf("8412-DEV-0001"), listOf("8412-DEV-0002")))

        assertEquals(2, rasterizer.pageCount(pdf))
    }

    @Test
    fun rendersAPageAtTheRequestedWidth() {
        val pdf = pdfWith(listOf(listOf("8412-DEV-0001")))

        val page = rasterizer.render(pdf, 1, 800)

        assertEquals(800, page.width)
        assertTrue("a rendered page must have height", page.height > 0)
    }

    @Test
    fun aBarcodeDrawnIntoAPdfIsReadBackOutOfIt() {
        // The whole chain, nothing stubbed: ZXing encodes, PdfDocument writes, PdfRenderer renders,
        // ZXing decodes.
        val pdf = pdfWith(listOf(listOf("8412-DEV-0001")))

        val decoded = decodeBarcodes(rasterizer.render(pdf, 1))

        assertEquals("8412-DEV-0001", decoded.single().value)
    }

    @Test
    fun aMultiPagePdfProposesOneTicketPerPage() {
        val pdf = pdfWith(listOf(listOf("8412-DEV-0001"), listOf("8412-DEV-0002")))

        val proposal = propose(pdf, rasterizer)

        assertEquals(
            listOf("8412-DEV-0001", "8412-DEV-0002"),
            proposal.tickets.map { it.barcode?.value },
        )
    }

    @Test
    fun aPageWithNoBarcodeIsExcludedFromTheSuggestion() {
        val pdf = pdfWith(listOf(emptyList(), listOf("8412-DEV-0003")))

        val proposal = propose(pdf, rasterizer)

        assertEquals(2, proposal.tickets.size)
        assertEquals(1, proposal.included().size)
    }

    @Test
    fun twoPassesOnOneSheetBecomeTwoTickets() {
        val pdf = pdfWith(listOf(listOf("8412-DEV-0004", "8412-DEV-0005")))

        val proposal = propose(pdf, rasterizer)

        assertEquals(2, proposal.tickets.size)
    }

    @Test
    fun aPhotographOfATicketIsReadDirectly() {
        val png = ByteArrayOutputStream().also { out ->
            barcodeBitmap("8412-DEV-0006", BarcodeFormat.QR_CODE, 600)
                .compress(Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()

        val proposal = propose(png, rasterizer)

        assertEquals("8412-DEV-0006", proposal.tickets.single().barcode?.value)
    }

    @Test
    fun theTemporaryCopyIsNotLeftBehind() {
        // While it exists it is an unencrypted ticket sitting outside the encrypted store.
        val before = context.cacheDir.listFiles()?.size ?: 0

        rasterizer.render(pdfWith(listOf(listOf("8412-DEV-0007"))), 1)

        assertEquals(before, context.cacheDir.listFiles()?.size ?: 0)
    }
}
