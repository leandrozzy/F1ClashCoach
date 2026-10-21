package com.leandro.f1clashcoach.capture

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.leandro.f1clashcoach.core.engine.TextLine
import kotlinx.coroutines.tasks.await

/** OCR local (ML Kit). Nada sai do aparelho. */
class OcrEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun read(bitmap: Bitmap): List<TextLine> {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks.flatMap { block ->
            block.lines.map { line ->
                val b = line.boundingBox
                if (b == null) TextLine(line.text)
                else TextLine(line.text, b.left / w, b.top / h, b.right / w, b.bottom / h)
            }
        }
    }

    fun close() = recognizer.close()
}
