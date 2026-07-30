package com.mateof.passvault.ui.ticket

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drawing a ticket's barcode.
 *
 * Always on a white background with a quiet zone, whatever the theme. A scanner reads reflected
 * light, so an inverted barcode on a dark surface fails at the gate — and the gate is the one place
 * where a failure costs the user something real. This is the reason the detail screen does not
 * simply inherit the dark theme.
 */
private val ZXING_FORMATS = mapOf(
    "QR_CODE" to BarcodeFormat.QR_CODE,
    "AZTEC" to BarcodeFormat.AZTEC,
    "PDF_417" to BarcodeFormat.PDF_417,
    "CODE_128" to BarcodeFormat.CODE_128,
    "CODE_39" to BarcodeFormat.CODE_39,
    "EAN_13" to BarcodeFormat.EAN_13,
    "DATA_MATRIX" to BarcodeFormat.DATA_MATRIX,
)

/** Two-dimensional formats are square; the linear ones need to be wide and short. */
private fun aspectFor(format: BarcodeFormat): Pair<Int, Int> = when (format) {
    BarcodeFormat.QR_CODE, BarcodeFormat.AZTEC, BarcodeFormat.DATA_MATRIX -> 900 to 900
    BarcodeFormat.PDF_417 -> 1000 to 400
    else -> 1000 to 320
}

@Composable
fun BarcodeImage(
    format: String,
    payload: String,
    modifier: Modifier = Modifier,
) {
    val zxingFormat = ZXING_FORMATS[format]

    // Encoding a dense PDF417 is milliseconds, not microseconds, and it happens while the screen is
    // appearing. produceState moves it off the composition so the transition does not stutter.
    val bitmap by produceState<Bitmap?>(initialValue = null, format, payload) {
        value = zxingFormat?.let {
            withContext(Dispatchers.Default) { encode(it, payload) }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            // White, always. A scanner reads reflected light.
            .background(Color.White)
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { image ->
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun encode(format: BarcodeFormat, value: String): Bitmap? = runCatching {
    val size: Pair<Int, Int> = aspectFor(format)
    val width: Int = size.first
    val height: Int = size.second
    val matrix: BitMatrix = MultiFormatWriter().encode(
        value,
        format,
        width,
        height,
        mapOf(EncodeHintType.MARGIN to 2),
    )
    Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            val offset = y * matrix.width
            for (x in 0 until matrix.width) {
                pixels[offset + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
    }
}.getOrNull()
