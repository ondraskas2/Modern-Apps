package com.vayunmathur.office.odf

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object OdfWriter {

    fun save(context: Context, sourceUri: Uri, document: OdfDocument, targetUri: Uri) {
        val contentXml = OdfSerializer.serialize(document)
        val metaXml = OdfSerializer.serializeMeta(document.metadata)
        val docImages = imagesOf(document)
        val written = mutableSetOf<String>()

        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zipOut ->
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                ZipInputStream(input).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        if (entry.name == "content.xml") {
                            zipOut.putNextEntry(ZipEntry("content.xml"))
                            zipOut.write(contentXml.toByteArray(Charsets.UTF_8))
                            zipOut.closeEntry()
                            written.add("content.xml")
                        } else if (entry.name == "meta.xml") {
                            zipOut.putNextEntry(ZipEntry("meta.xml"))
                            zipOut.write(metaXml.toByteArray(Charsets.UTF_8))
                            zipOut.closeEntry()
                            written.add("meta.xml")
                        } else if (!entry.isDirectory) {
                            zipOut.putNextEntry(ZipEntry(entry.name))
                            zipIn.copyTo(zipOut)
                            zipOut.closeEntry()
                            written.add(entry.name)
                        }
                        entry = zipIn.nextEntry
                    }
                }
            }
            // If the source had no content.xml (e.g. brand-new flat/empty), ensure one is written.
            if ("content.xml" !in written) {
                zipOut.putNextEntry(ZipEntry("content.xml"))
                zipOut.write(contentXml.toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()
                written.add("content.xml")
            }
            // Write any newly-inserted images that weren't part of the original package. (K72/K73)
            for ((path, bytes) in docImages) {
                if (path == "inline" || path in written || bytes.isEmpty()) continue
                zipOut.putNextEntry(ZipEntry(path))
                zipOut.write(bytes)
                zipOut.closeEntry()
                written.add(path)
            }
        }

        context.contentResolver.openOutputStream(targetUri, "wt")?.use { output ->
            buffer.writeTo(output)
        }
    }

    private fun imagesOf(document: OdfDocument): Map<String, ByteArray> = when (document) {
        is OdfDocument.TextDocument -> document.images
        is OdfDocument.Spreadsheet -> document.images
        is OdfDocument.Presentation -> document.images
        is OdfDocument.Drawing -> document.images
    }

    fun saveAs(context: Context, sourceUri: Uri, document: OdfDocument, targetUri: Uri) {
        save(context, sourceUri, document, targetUri)
    }
}
