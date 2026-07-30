package com.mateof.passvault.ingest

import com.google.common.truth.Truth.assertThat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test

/**
 * Deciding what a document contains.
 *
 * The barcodes are real: ZXing encodes them and ZXing reads them back, so the decoder is exercised
 * rather than stubbed. What is faked is only the rasterizer, because turning a PDF into pixels is a
 * platform job with no JVM equivalent — `PdfRendererTest` covers that part on a device.
 *
 * The behaviour being pinned is the one the server also implements, because the same file dropped
 * on a phone and on a server has to produce the same suggestion.
 */
class IngestTest {

    /** A page carrying the given payloads, rendered the way a real rasterizer would hand it over. */
    private fun page(pageNumber: Int, vararg payloads: Pair<String, BarcodeFormat>): RasterPage {
        val cells = payloads.map { (text, format) ->
            MultiFormatWriter().encode(text, format, 400, 400)
        }
        val width = 420 * cells.size.coerceAtLeast(1)
        val height = 420
        val pixels = IntArray(width * height) { 0xFFFFFFFF.toInt() }
        cells.forEachIndexed { index, matrix -> matrix.blitInto(pixels, width, index * 420 + 10, 10) }
        return RasterPage(pageNumber, width, height, pixels)
    }

    /**
     * A page laid out the way a ticket vendor lays one out.
     *
     * The difference from [page] is the whole point of these three tests. A symbol that fills the
     * frame is the easy case, and the one a synthetic fixture falls into without meaning to; what
     * arrives from a vendor is a modest barcode high on an otherwise empty A4 sheet, rendered at
     * the width ingestion actually uses. That is a different problem for a detector, and the
     * emulator found it: a real four-page PDF lost its Aztec while the tight fixture above passed.
     */
    private fun sheet(pageNumber: Int, payload: String, format: BarcodeFormat): RasterPage {
        val width = IngestLimits.RENDER_WIDTH
        // A4 at the proportions and placement the fixtures in the server repository use: 180pt
        // wide, 60pt from the left edge, 520pt up from the bottom of a 595x842pt page.
        val height = width * 842 / 595
        val symbol = width * 180 / 595
        val matrix = MultiFormatWriter().encode(payload, format, symbol, symbol)
        val pixels = IntArray(width * height) { 0xFFFFFFFF.toInt() }
        matrix.blitInto(pixels, width, width * 60 / 595, height - width * 700 / 595)
        return RasterPage(pageNumber, width, height, pixels)
    }

    private fun BitMatrix.blitInto(pixels: IntArray, stride: Int, offsetX: Int, offsetY: Int) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (this[x, y]) pixels[(offsetY + y) * stride + offsetX + x] = 0xFF000000.toInt()
            }
        }
    }

    /** Stands in for PdfRenderer, handing back pages that were prepared above. */
    private fun rasterizer(vararg pages: RasterPage) = object : PageRasterizer {
        override fun pageCount(pdf: ByteArray) = pages.size
        override fun render(pdf: ByteArray, pageNumber: Int, widthPx: Int) = pages[pageNumber - 1]
    }

    private val pdfBytes = "%PDF-1.7\n%%EOF\n".toByteArray()

    private fun pkpass(vararg barcodes: Pair<String, String>): ByteArray {
        val pass = buildString {
            append("""{"description":"Festival do Norte 2026","barcodes":[""")
            append(
                barcodes.joinToString(",") { (format, message) ->
                    """{"format":"$format","message":"$message"}"""
                },
            )
            append("]}")
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("pass.json"))
            zip.write(pass.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `a PDF is recognised from its bytes`() {
        assertThat(detectMediaKind(pdfBytes)).isEqualTo(MediaKind.PDF)
    }

    @Test
    fun `an Apple Wallet pass is recognised, which is a ZIP holding a pass_json`() {
        assertThat(detectMediaKind(pkpass("PKBarcodeFormatQR" to "x"))).isEqualTo(MediaKind.PKPASS)
    }

    @Test
    fun `a ZIP that is not a pass is refused rather than accepted as one`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("nothing".toByteArray())
            zip.closeEntry()
        }

        val thrown = runCatching { detectMediaKind(out.toByteArray()) }.exceptionOrNull()

        assertThat((thrown as IngestException).code).isEqualTo(IngestError.UNSUPPORTED_FILE)
    }

    @Test
    fun `a file it does not know is refused`() {
        val thrown = runCatching { detectMediaKind("hello".toByteArray()) }.exceptionOrNull()

        assertThat((thrown as IngestException).code).isEqualTo(IngestError.UNSUPPORTED_FILE)
    }

    @Test
    fun `a barcode encoded by ZXing is read back by ZXing`() {
        val decoded = decodeBarcodes(page(1, "8412-ING-0001" to BarcodeFormat.QR_CODE))

        assertThat(decoded.map { it.value }).containsExactly("8412-ING-0001")
    }

    @Test
    fun `an Aztec code is read, which is what rail and many venues use`() {
        val decoded = decodeBarcodes(page(1, "8412-AZTEC-0001" to BarcodeFormat.AZTEC))

        assertThat(decoded.single().format).isEqualTo("AZTEC")
    }

    @Test
    fun `a QR on a whole sheet is read, not just one that fills the frame`() {
        val decoded = decodeBarcodes(sheet(1, "8412-SHEET-0001", BarcodeFormat.QR_CODE))

        assertThat(decoded.single().value).isEqualTo("8412-SHEET-0001")
    }

    @Test
    fun `an Aztec on a whole sheet is read, not just one that fills the frame`() {
        val decoded = decodeBarcodes(sheet(1, "8412-SHEET-0002", BarcodeFormat.AZTEC))

        assertThat(decoded.single().value).isEqualTo("8412-SHEET-0002")
    }

    @Test
    fun `a Data Matrix on a whole sheet is read, not just one that fills the frame`() {
        val decoded = decodeBarcodes(sheet(1, "8412-SHEET-0003", BarcodeFormat.DATA_MATRIX))

        assertThat(decoded.single().value).isEqualTo("8412-SHEET-0003")
    }

    @Test
    fun `a multi-page PDF proposes one ticket per page`() {
        val proposal = propose(
            pdfBytes,
            rasterizer(
                page(1, "8412-ING-0001" to BarcodeFormat.QR_CODE),
                page(2, "8412-ING-0002" to BarcodeFormat.QR_CODE),
            ),
        )

        assertThat(proposal.tickets).hasSize(2)
    }

    @Test
    fun `it reads each page's barcode`() {
        val proposal = propose(
            pdfBytes,
            rasterizer(
                page(1, "8412-ING-0001" to BarcodeFormat.QR_CODE),
                page(2, "8412-ING-0002" to BarcodeFormat.QR_CODE),
            ),
        )

        assertThat(proposal.tickets.map { it.barcode?.value })
            .containsExactly("8412-ING-0001", "8412-ING-0002")
            .inOrder()
    }

    @Test
    fun `it never saves anything, only proposes`() {
        val proposal = propose(pdfBytes, rasterizer(page(1, "8412-ING-0001" to BarcodeFormat.QR_CODE)))

        assertThat(proposal.requiresReview).isTrue()
    }

    @Test
    fun `a page of instructions is kept but left out of the suggestion`() {
        val blank = RasterPage(1, 200, 200, IntArray(200 * 200) { 0xFFFFFFFF.toInt() })

        val proposal = propose(
            pdfBytes,
            rasterizer(blank, page(2, "8412-ING-0002" to BarcodeFormat.QR_CODE)),
        )

        assertThat(proposal.tickets).hasSize(2)
        assertThat(proposal.included()).hasSize(1)
    }

    @Test
    fun `a page with no barcode is warned about`() {
        val blank = RasterPage(1, 200, 200, IntArray(200 * 200) { 0xFFFFFFFF.toInt() })

        val proposal = propose(pdfBytes, rasterizer(blank))

        assertThat(proposal.warnings).contains(ProposalWarning.NO_BARCODE)
    }

    @Test
    fun `two passes on one sheet become two tickets, not one`() {
        val proposal = propose(
            pdfBytes,
            rasterizer(
                page(
                    1,
                    "8412-ING-0003" to BarcodeFormat.QR_CODE,
                    "8412-ING-0004" to BarcodeFormat.QR_CODE,
                ),
            ),
        )

        assertThat(proposal.tickets.mapNotNull { it.barcode?.value })
            .containsExactly("8412-ING-0003", "8412-ING-0004")
    }

    @Test
    fun `and the split is flagged, because it is a guess`() {
        val proposal = propose(
            pdfBytes,
            rasterizer(
                page(
                    1,
                    "8412-ING-0003" to BarcodeFormat.QR_CODE,
                    "8412-ING-0004" to BarcodeFormat.QR_CODE,
                ),
            ),
        )

        assertThat(proposal.warnings).contains(ProposalWarning.MULTIPLE_BARCODES)
    }

    @Test
    fun `a summary page repeating a barcode is excluded, so one seat is not imported twice`() {
        val proposal = propose(
            pdfBytes,
            rasterizer(
                page(1, "8412-ING-0005" to BarcodeFormat.QR_CODE),
                page(2, "8412-ING-0006" to BarcodeFormat.QR_CODE),
                page(3, "8412-ING-0005" to BarcodeFormat.QR_CODE),
            ),
        )

        assertThat(proposal.included()).hasSize(2)
        assertThat(proposal.warnings).contains(ProposalWarning.DUPLICATE_BARCODE)
    }

    @Test
    fun `the first occurrence is the one that is kept`() {
        val proposal = propose(
            pdfBytes,
            rasterizer(
                page(1, "8412-ING-0007" to BarcodeFormat.QR_CODE),
                page(2, "8412-ING-0007" to BarcodeFormat.QR_CODE),
            ),
        )

        assertThat(proposal.included().single().pageNumber).isEqualTo(1)
    }

    @Test
    fun `a PDF with no rasterizer says so instead of importing a ticket with no barcode`() {
        val thrown = runCatching { propose(pdfBytes) }.exceptionOrNull()

        assertThat((thrown as IngestException).code).isEqualTo(IngestError.RASTERIZER_UNAVAILABLE)
    }

    @Test
    fun `an Apple Wallet pass proposes one ticket per barcode`() {
        val proposal = propose(pkpass("PKBarcodeFormatQR" to "8412-PKPASS-0001"))

        assertThat(proposal.tickets.single().barcode?.value).isEqualTo("8412-PKPASS-0001")
    }

    @Test
    fun `it maps Apple's format names onto the project's`() {
        val proposal = propose(pkpass("PKBarcodeFormatPDF417" to "8412-PKPASS-0417"))

        assertThat(proposal.tickets.single().barcode?.format).isEqualTo("PDF_417")
    }

    @Test
    fun `it uses the pass description as the label`() {
        val proposal = propose(pkpass("PKBarcodeFormatQR" to "8412-PKPASS-0001"))

        assertThat(proposal.tickets.single().suggestedLabel).isEqualTo("Festival do Norte 2026")
    }

    @Test
    fun `a pass with no barcode proposes nothing importable but still reports itself`() {
        val proposal = propose(pkpass())

        assertThat(proposal.included()).isEmpty()
        assertThat(proposal.warnings).contains(ProposalWarning.PKPASS_NO_BARCODE)
    }

    @Test
    fun `a file over the size limit is refused before anything is parsed`() {
        val huge = ByteArray(IngestLimits.FILE_BYTES + 1)

        val thrown = runCatching { propose(huge) }.exceptionOrNull()

        assertThat((thrown as IngestException).code).isEqualTo(IngestError.FILE_TOO_LARGE)
    }
}
