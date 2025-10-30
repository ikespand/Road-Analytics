package com.ikespand.roadanalytics.util

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

// Simple box datatype used for drawing onto the bitmap
data class Box(val x:Int, val y:Int, val w:Int, val h:Int, val label:String, val conf:Float)

object ImageSaver {

    /**
     * Draw boxes on a copy of [src] and save to a Gallery-visible album:
     *  - API 29+: MediaStore path = Pictures/[albumName]
     *  - API < 29: /sdcard/Pictures/[albumName] and trigger a media scan
     *
     * @return Pair<fileName, contentUriOrFileUri>
     */
    fun saveToGallery(
        context: Context,
        src: Bitmap,
        boxes: List<Box>,
        albumName: String = "PotholeDetections",
        prefix: String = "pothole"
    ): Pair<String, Uri?> {

        val annotated = annotate(src, boxes)
        val fileName = "${prefix}_${System.currentTimeMillis()}.png"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$albumName")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

            var out: OutputStream? = null
            try {
                out = uri?.let { resolver.openOutputStream(it) }
                requireNotNull(out) { "Could not open output stream for MediaStore URI" }
                annotated.compress(Bitmap.CompressFormat.PNG, 100, out)
            } finally {
                out?.close()
                uri?.let {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(it, values, null, null)
                }
            }
            fileName to uri
        } else {
            // Legacy (<29): write to public Pictures dir + scan
            val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val albumDir = File(pictures, albumName).apply { if (!exists()) mkdirs() }
            val outFile = File(albumDir, fileName)

            FileOutputStream(outFile).use { fos ->
                annotated.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }

            MediaScannerConnection.scanFile(
                context,
                arrayOf(outFile.absolutePath),
                arrayOf("image/png"),
                null
            )

            fileName to Uri.fromFile(outFile)
        }
    }

    /** Keep this for other use-cases (not Gallery). */
    fun saveAnnotated(src: Bitmap, boxes: List<Box>, outDir: File, prefix: String = "det"): String {
        val bmp = annotate(src, boxes)
        val fileName = "${prefix}_${System.currentTimeMillis()}.png"
        val file = File(outDir, fileName)
        FileOutputStream(file).use { fos -> bmp.compress(Bitmap.CompressFormat.PNG, 100, fos) }
        return fileName
    }

    // ---- drawing helper ----
    private fun annotate(src: Bitmap, boxes: List<Box>): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bmp)

        val pBox = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
        val pBg = Paint().apply {
            style = Paint.Style.FILL
            alpha = 180
        }
        val pTxt = Paint().apply {
            isAntiAlias = true
            textSize = 36f
        }

        boxes.forEach { b ->
            val rect = Rect(b.x, b.y, b.x + b.w, b.y + b.h)
            canvas.drawRect(rect, pBox)

            val label = "${b.label} ${(b.conf * 100).toInt()}%"
            val tb = Rect()
            pTxt.getTextBounds(label, 0, label.length, tb)
            val pad = 8
            val bgRect = Rect(
                rect.left,
                (rect.top - tb.height() - 2 * pad).coerceAtLeast(0),
                rect.left + tb.width() + 2 * pad,
                rect.top
            )
            canvas.drawRect(bgRect, pBg)
            canvas.drawText(label, (bgRect.left + pad).toFloat(), (bgRect.bottom - pad).toFloat(), pTxt)
        }

        return bmp
    }
}
