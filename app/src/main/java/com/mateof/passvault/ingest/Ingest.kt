package com.mateof.passvault.ingest

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turning a document into proposed tickets.
 *
 * The counterpart of `packages/ingest` on the server, and it makes the same promise: this produces
 * a **proposal**, never a saved result. Splitting a PDF one ticket per page is right most of the
 * time and wrong often enough to matter — vendors put two passes on a sheet, lead with a page of
 * instructions, or repeat a summary page carrying a barcode that is already a ticket. A process
 * that applied its guess silently would create phantom tickets somebody then has to notice.
 */
enum class IngestError {
    UNSUPPORTED_FILE,
    FILE_TOO_LARGE,
    DAMAGED_FILE,
    TOO_MANY_PAGES,
    PKPASS_MALFORMED,
    RASTERIZER_UNAVAILABLE,
}

class IngestException(val code: IngestError, message: String, cause: Throwable? = null) :
    Exception(message, cause)

object IngestLimits {
    const val FILE_BYTES = 64 * 1024 * 1024
    const val PAGES = 256
    /** Wide enough for a dense PDF417 without producing bitmaps a phone cannot hold. */
    const val RENDER_WIDTH = 1600
    const val BARCODES_PER_PAGE = 8
}

enum class MediaKind { PDF, PNG, JPEG, PKPASS }

/**
 * Identified from the bytes, never the name.
 *
 * A ticket arrives from a messaging app, where the extension is whatever the sender's phone decided
 * and `.pkpass` is routinely renamed on the way. Magic bytes are the only reliable signal.
 */
fun detectMediaKind(bytes: ByteArray): MediaKind {
    fun startsWith(vararg signature: Int) =
        bytes.size >= signature.size && signature.withIndex().all { (i, b) -> bytes[i] == b.toByte() }

    return when {
        startsWith(0x25, 0x50, 0x44, 0x46) -> MediaKind.PDF
        startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> MediaKind.PNG
        startsWith(0xFF, 0xD8, 0xFF) -> MediaKind.JPEG
        startsWith(0x50, 0x4B, 0x03, 0x04) && looksLikePkpass(bytes) -> MediaKind.PKPASS
        else -> throw IngestException(
            IngestError.UNSUPPORTED_FILE,
            "not a PDF, PNG, JPEG or Apple Wallet pass",
        )
    }
}

private fun looksLikePkpass(bytes: ByteArray): Boolean = runCatching {
    ZipInputStream(bytes.inputStream()).use { zip ->
        generateSequence { zip.nextEntry }.any { it.name == "pass.json" }
    }
}.getOrDefault(false)

/** A page as pixels. Kept as ARGB so ZXing can read it without another conversion. */
data class RasterPage(val pageNumber: Int, val width: Int, val height: Int, val pixels: IntArray) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * Rendering PDF pages.
 *
 * Behind an interface for the same reason as the vault key: `PdfRenderer` is a platform class with
 * no JVM equivalent, and orchestration that can only run on a device does not get tested. The
 * Android implementation is a thin adapter; the deciding logic below is exercised against a fake.
 */
interface PageRasterizer {
    fun pageCount(pdf: ByteArray): Int
    fun render(pdf: ByteArray, pageNumber: Int, widthPx: Int = IngestLimits.RENDER_WIDTH): RasterPage
}

data class DecodedBarcode(val format: String, val value: String)

private val ZXING_TO_FORMAT = mapOf(
    BarcodeFormat.QR_CODE to "QR_CODE",
    BarcodeFormat.AZTEC to "AZTEC",
    BarcodeFormat.PDF_417 to "PDF_417",
    BarcodeFormat.CODE_128 to "CODE_128",
    BarcodeFormat.CODE_39 to "CODE_39",
    BarcodeFormat.EAN_13 to "EAN_13",
    BarcodeFormat.DATA_MATRIX to "DATA_MATRIX",
)

/**
 * Reads every barcode on a page, not just the first.
 *
 * A page with two barcodes is a real case — two passes printed on one sheet — and the caller has to
 * decide how they split into tickets rather than being handed a guess.
 */
fun decodeBarcodes(page: RasterPage): List<DecodedBarcode> {
    val source = RGBLuminanceSource(page.width, page.height, page.pixels)

    val wholePage = decodeRegion(source)
    if (wholePage.isNotEmpty()) return wholePage

    // Nothing on the whole page. That is usually an instructions sheet, but it is also what a
    // centre-seeking detector reports for a symbol sitting off to one side, so it is worth a
    // second look before the page is written off. See [decodeByTiles].
    return decodeByTiles(source)
}

/**
 * A second pass over overlapping windows of the page.
 *
 * ZXing's Aztec and Data Matrix detectors look for the symbol by growing a white rectangle
 * outward from the centre of the image. On a ticket sheet — one modest barcode high on an
 * otherwise blank A4 page — that rectangle swallows the whole page and its centre lands on empty
 * paper, so the symbol is never found. QR and PDF417 are unaffected: their detectors scan rows
 * for a pattern and do not care where on the page it sits.
 *
 * This is why the app read a real vendor PDF's QR and PDF417 and silently dropped its Aztec while
 * the server read all three: the server's ZXing is the C++ implementation through WebAssembly,
 * whose detector does not have this bias. The unit tests missed it because their fixture pages
 * were barely larger than the barcode, which is the one geometry where the bias does not bite.
 *
 * Cropping each window so the symbol falls near its centre gives those detectors the frame they
 * need. It runs only when the whole-page pass found nothing, so an ordinary page costs nothing
 * extra; the price is paid on pages that would otherwise have been lost, and on genuinely blank
 * ones. A page holding both a QR and an Aztec still yields only the QR, because a hit on the
 * whole page skips this pass — accepted rather than paying for the tiles on every page.
 */
private fun decodeByTiles(source: RGBLuminanceSource): List<DecodedBarcode> {
    val window = minOf(source.width, source.height) / 2
    if (window < MIN_TILE) return emptyList()
    // Two thirds of overlap. Half was the first guess and was not enough: windows have to overlap
    // by more than the symbol is wide for one of them to contain it whole, and a symbol that lands
    // across a seam is invisible to every window that sees only part of it.
    val step = window / 3

    val found = LinkedHashMap<String, DecodedBarcode>()
    var top = 0
    while (top < source.height && found.size < IngestLimits.BARCODES_PER_PAGE) {
        var left = 0
        val height = minOf(window, source.height - top)
        while (left < source.width && found.size < IngestLimits.BARCODES_PER_PAGE) {
            val width = minOf(window, source.width - left)
            if (width >= MIN_TILE && height >= MIN_TILE) {
                for (decoded in decodeRegion(source.crop(left, top, width, height))) {
                    // Keyed by value: overlapping windows see the same symbol more than once, and
                    // one barcode read twice is one barcode.
                    found.putIfAbsent(decoded.value, decoded)
                }
            }
            if (left + window >= source.width) break
            left += step
        }
        if (top + window >= source.height) break
        top += step
    }
    return found.values.toList()
}

private fun decodeRegion(source: com.google.zxing.LuminanceSource): List<DecodedBarcode> = runCatching {
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to ZXING_TO_FORMAT.keys.toList(),
    )
    GenericMultipleBarcodeReader(MultiFormatReader())
        .decodeMultiple(bitmap, hints)
        .take(IngestLimits.BARCODES_PER_PAGE)
        .mapNotNull { result ->
            ZXING_TO_FORMAT[result.barcodeFormat]?.let { DecodedBarcode(it, result.text) }
        }
}.getOrDefault(emptyList())

/** Below this a window is too small to hold a readable symbol, and only costs time. */
private const val MIN_TILE = 64

enum class ProposalWarning {
    NO_BARCODE,
    MULTIPLE_BARCODES,
    DUPLICATE_BARCODE,
    PKPASS_NO_BARCODE,
}

data class ProposedTicket(
    val index: Int,
    val suggestedLabel: String,
    val barcode: DecodedBarcode?,
    val pageNumber: Int?,
    /** What the proposal suggests. The user can flip it. */
    val include: Boolean,
    val warnings: List<ProposalWarning>,
)

data class IngestProposal(
    val kind: MediaKind,
    val pageCount: Int,
    val tickets: List<ProposedTicket>,
    val warnings: List<ProposalWarning>,
    /** Always true. Present so no caller can mistake a proposal for a finished import. */
    val requiresReview: Boolean = true,
)

/**
 * Proposes tickets from a document.
 *
 * The rules match the server's, because the same file dropped on a phone and on a server should
 * produce the same suggestion:
 *
 *   * a page with no barcode is kept but excluded — an instructions sheet should not be imported,
 *     and a page whose barcode merely failed to decode should not vanish either;
 *   * a page with two barcodes produces two tickets and a warning, because the split is a guess;
 *   * a barcode that appears twice is flagged and the repeat excluded, since importing both would
 *     create the same seat twice and the duplicate would be discovered at the turnstile.
 */
fun propose(
    bytes: ByteArray,
    rasterizer: PageRasterizer? = null,
    labelPrefix: String? = null,
): IngestProposal {
    if (bytes.size > IngestLimits.FILE_BYTES) {
        throw IngestException(IngestError.FILE_TOO_LARGE, "file is ${bytes.size} bytes")
    }
    return when (val kind = detectMediaKind(bytes)) {
        MediaKind.PDF -> proposeFromPdf(bytes, rasterizer, labelPrefix)
        MediaKind.PNG, MediaKind.JPEG -> proposeFromImage(kind, bytes, rasterizer, labelPrefix)
        MediaKind.PKPASS -> proposeFromPkpass(bytes, labelPrefix)
    }
}

private fun proposeFromPdf(
    bytes: ByteArray,
    rasterizer: PageRasterizer?,
    labelPrefix: String?,
): IngestProposal {
    val renderer = rasterizer ?: throw IngestException(
        IngestError.RASTERIZER_UNAVAILABLE,
        "reading barcodes from a PDF needs a page rasterizer",
    )
    val pages = renderer.pageCount(bytes)
    if (pages > IngestLimits.PAGES) {
        throw IngestException(IngestError.TOO_MANY_PAGES, "document has $pages pages")
    }

    val tickets = mutableListOf<ProposedTicket>()
    val warnings = mutableListOf<ProposalWarning>()

    for (pageNumber in 1..pages) {
        val found = decodeBarcodes(renderer.render(bytes, pageNumber))
        if (found.isEmpty()) {
            warnings += ProposalWarning.NO_BARCODE
            tickets += ProposedTicket(
                index = tickets.size,
                suggestedLabel = label(labelPrefix, pageNumber, null),
                barcode = null,
                pageNumber = pageNumber,
                include = false,
                warnings = listOf(ProposalWarning.NO_BARCODE),
            )
            continue
        }
        if (found.size > 1) {
            warnings += ProposalWarning.MULTIPLE_BARCODES
        }
        found.forEachIndexed { ordinal, barcode ->
            tickets += ProposedTicket(
                index = tickets.size,
                suggestedLabel = label(labelPrefix, pageNumber, if (found.size > 1) ordinal + 1 else null),
                barcode = barcode,
                pageNumber = pageNumber,
                include = true,
                warnings = if (found.size > 1) listOf(ProposalWarning.MULTIPLE_BARCODES) else emptyList(),
            )
        }
    }

    return IngestProposal(MediaKind.PDF, pages, flagDuplicates(tickets, warnings), warnings)
}

private fun proposeFromImage(
    kind: MediaKind,
    bytes: ByteArray,
    rasterizer: PageRasterizer?,
    labelPrefix: String?,
): IngestProposal {
    // An image is decoded through the same rasterizer, which knows how to turn encoded bytes into
    // pixels on this platform. Without one there is nothing to decode from.
    val page = rasterizer?.render(bytes, 1)
    val found = page?.let { decodeBarcodes(it) }.orEmpty()
    val warnings = mutableListOf<ProposalWarning>()

    val tickets = if (found.isEmpty()) {
        warnings += ProposalWarning.NO_BARCODE
        listOf(
            ProposedTicket(
                index = 0,
                suggestedLabel = label(labelPrefix, 1, null),
                barcode = null,
                pageNumber = 1,
                // Unlike a PDF page, an image the user deliberately picked is probably a ticket even
                // if the barcode did not decode — a photograph at an angle, say.
                include = true,
                warnings = listOf(ProposalWarning.NO_BARCODE),
            ),
        )
    } else {
        if (found.size > 1) warnings += ProposalWarning.MULTIPLE_BARCODES
        found.mapIndexed { ordinal, barcode ->
            ProposedTicket(
                index = ordinal,
                suggestedLabel = label(labelPrefix, 1, if (found.size > 1) ordinal + 1 else null),
                barcode = barcode,
                pageNumber = 1,
                include = true,
                warnings = emptyList(),
            )
        }
    }

    return IngestProposal(kind, 1, flagDuplicates(tickets.toMutableList(), warnings), warnings)
}

private val PKPASS_FORMATS = mapOf(
    "PKBarcodeFormatQR" to "QR_CODE",
    "PKBarcodeFormatPDF417" to "PDF_417",
    "PKBarcodeFormatAztec" to "AZTEC",
    "PKBarcodeFormatCode128" to "CODE_128",
)

private fun proposeFromPkpass(bytes: ByteArray, labelPrefix: String?): IngestProposal {
    val entries = runCatching { unzip(bytes) }.getOrElse {
        throw IngestException(IngestError.PKPASS_MALFORMED, "pass is not a readable ZIP", it)
    }
    val passJson = entries["pass.json"]
        ?: throw IngestException(IngestError.PKPASS_MALFORMED, "pass holds no pass.json")

    // kotlinx.serialization rather than org.json: the platform's JSON is a stub in a JVM unit
    // test, so parsing written against it can only be exercised on a device — and this is exactly
    // the parsing that has to handle whatever a vendor puts in a pass.
    val pass = runCatching {
        Json.parseToJsonElement(String(passJson, Charsets.UTF_8)).jsonObject
    }.getOrElse {
        throw IngestException(IngestError.PKPASS_MALFORMED, "pass.json is not valid JSON", it)
    }

    val barcodes = buildList<JsonObject> {
        (pass["barcodes"] as? kotlinx.serialization.json.JsonArray)?.let { array ->
            array.forEach { element -> runCatching { add(element.jsonObject) } }
        }
        // Deprecated by Apple in favour of the array, and still emitted by plenty of vendors.
        (pass["barcode"] as? JsonObject)?.let { add(it) }
    }.mapNotNull { entry ->
        val format = PKPASS_FORMATS[entry.string("format")] ?: return@mapNotNull null
        val message = entry.string("message")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        DecodedBarcode(format, message)
    }

    val name = pass.string("description")?.takeIf { it.isNotEmpty() } ?: labelPrefix ?: "Pass"
    if (barcodes.isEmpty()) {
        return IngestProposal(
            MediaKind.PKPASS,
            1,
            listOf(
                ProposedTicket(0, name, null, null, include = false, warnings = listOf(ProposalWarning.PKPASS_NO_BARCODE)),
            ),
            listOf(ProposalWarning.PKPASS_NO_BARCODE),
        )
    }

    return IngestProposal(
        MediaKind.PKPASS,
        1,
        barcodes.mapIndexed { index, barcode ->
            ProposedTicket(
                index = index,
                suggestedLabel = if (barcodes.size > 1) "$name ${index + 1}" else name,
                barcode = barcode,
                pageNumber = null,
                include = true,
                warnings = emptyList(),
            )
        },
        emptyList(),
    )
}

private fun JsonObject.string(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
    ZipInputStream(bytes.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            put(entry.name, zip.readBytesLimited())
        }
    }
}

private fun ZipInputStream.readBytesLimited(): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read <= 0) break
        total += read
        if (total > IngestLimits.FILE_BYTES) {
            throw IngestException(IngestError.FILE_TOO_LARGE, "entry is too large")
        }
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/**
 * Flags a barcode that appears more than once.
 *
 * Almost always a summary or cover page carrying the same code as a real ticket. Importing both
 * would produce two tickets for one seat, and the duplicate would be discovered at the turnstile.
 */
private fun flagDuplicates(
    tickets: MutableList<ProposedTicket>,
    warnings: MutableList<ProposalWarning>,
): List<ProposedTicket> {
    val seen = mutableSetOf<String>()
    return tickets.map { ticket ->
        val value = ticket.barcode?.value ?: return@map ticket
        if (seen.add(value)) {
            ticket
        } else {
            warnings += ProposalWarning.DUPLICATE_BARCODE
            ticket.copy(include = false, warnings = ticket.warnings + ProposalWarning.DUPLICATE_BARCODE)
        }
    }
}

private fun label(prefix: String?, pageNumber: Int, ordinal: Int?): String =
    listOfNotNull(prefix, pageNumber.toString() + (ordinal?.let { ".$it" } ?: "")).joinToString(" ")

/** The tickets the user has not excluded. What a confirmation step actually saves. */
fun IngestProposal.included(): List<ProposedTicket> = tickets.filter { it.include }
