package com.ikespand.roadanalytics.util

import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ZipUtils {
    fun zipDir(inputDir: File, outputZip: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZip))).use { zos ->
            inputDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val entryName = inputDir.toPath().relativize(file.toPath()).toString()
                zos.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
}
