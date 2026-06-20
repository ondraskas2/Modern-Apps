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
                        } else if (!entry.isDirectory) {
                            zipOut.putNextEntry(ZipEntry(entry.name))
                            zipIn.copyTo(zipOut)
                            zipOut.closeEntry()
                        }
                        entry = zipIn.nextEntry
                    }
                }
            }
        }

        context.contentResolver.openOutputStream(targetUri, "wt")?.use { output ->
            buffer.writeTo(output)
        }
    }

    fun saveAs(context: Context, sourceUri: Uri, document: OdfDocument, targetUri: Uri) {
        save(context, sourceUri, document, targetUri)
    }
}
