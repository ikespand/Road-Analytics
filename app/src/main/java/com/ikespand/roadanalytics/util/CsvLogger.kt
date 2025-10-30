package com.ikespand.roadanalytics.util

import android.content.Context
import java.io.File
import java.io.FileWriter

data class DetectionRecord(
    val timestampMs: Long,
    val label: String,
    val confidence: Float,
    val x: Int, val y: Int, val w: Int, val h: Int,
    val latitude: Double?, val longitude: Double?,
    val imageName: String
)

class CsvLogger(private val context: Context) {
    private val dir: File by lazy {
        File(context.filesDir, "detections").apply { if (!exists()) mkdirs() }
    }
    val csvFile: File by lazy { File(dir, "detections.csv") }

    @Synchronized fun append(record: DetectionRecord) {
        val header = "timestamp_ms,label,confidence,x,y,w,h,lat,lon,image\n"
        val newFile = !csvFile.exists()
        FileWriter(csvFile, true).use { fw ->
            if (newFile) fw.write(header)
            fw.append(
                "${record.timestampMs}," +
                        "${record.label}," +
                        "${"%.3f".format(record.confidence)}," +
                        "${record.x},${record.y},${record.w},${record.h}," +
                        "${record.latitude ?: ""},${record.longitude ?: ""}," +
                        record.imageName + "\n"
            )
        }
    }

    fun imagesDir(): File = File(dir, "images").apply { if (!exists()) mkdirs() }
    fun baseDir(): File = dir
}
