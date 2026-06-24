package com.vayunmathur.office.odf

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object OdfWriter {

    fun save(context: Context, sourceUri: Uri?, document: OdfDocument, targetUri: Uri) {
        val result = OdfSerializer.serializePackaged(document)
        val contentXml = result.contentXml
        val metaXml = OdfSerializer.serializeMeta(document.metadata)
        val settingsXml = OdfSerializer.serializeSettings(document)
        val docImages = imagesOf(document)
        val written = mutableSetOf<String>()
        // Media types for generated package parts (embedded chart objects, inline images).
        val extraManifest = LinkedHashMap<String, String>(result.manifest)

        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zipOut ->
            val source = if (sourceUri != null) context.contentResolver.openInputStream(sourceUri) else null
            if (source != null) {
                source.use { input ->
                    ZipInputStream(input).use { zipIn ->
                        var entry = zipIn.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            when {
                                name == "content.xml" -> {
                                    writeEntry(zipOut, "content.xml", contentXml.toByteArray(Charsets.UTF_8)); written.add(name)
                                }
                                name == "meta.xml" -> {
                                    writeEntry(zipOut, "meta.xml", metaXml.toByteArray(Charsets.UTF_8)); written.add(name)
                                }
                                // When we have generated freeze-pane settings, replace; otherwise copy source as-is. (C2)
                                name == "settings.xml" -> if (settingsXml != null) {
                                    writeEntry(zipOut, "settings.xml", settingsXml.toByteArray(Charsets.UTF_8)); written.add(name)
                                } else {
                                    zipOut.putNextEntry(ZipEntry(name)); zipIn.copyTo(zipOut); zipOut.closeEntry(); written.add(name)
                                }
                                // Regenerated below; never copy the old manifest.
                                name == "META-INF/manifest.xml" -> {}
                                // Patch page geometry into the copied styles.xml so Page Setup edits persist. (Priority 7)
                                name == "styles.xml" -> {
                                    val ps = (document as? OdfDocument.TextDocument)?.pageSetup
                                    val original = zipIn.readBytes().toString(Charsets.UTF_8)
                                    val out = if (ps != null) patchStylesXml(original, ps) else original
                                    writeEntry(zipOut, "styles.xml", out.toByteArray(Charsets.UTF_8)); written.add(name)
                                }
                                !entry.isDirectory -> {
                                    zipOut.putNextEntry(ZipEntry(name)); zipIn.copyTo(zipOut); zipOut.closeEntry(); written.add(name)
                                }
                            }
                            entry = zipIn.nextEntry
                        }
                    }
                }
            } else {
                // Brand-new document with no source package: write the special mimetype entry first,
                // uncompressed, as required by ODF. (Priority 1: enable saving new documents)
                writeStored(zipOut, "mimetype", documentMime(document).toByteArray(Charsets.US_ASCII))
                written.add("mimetype")
            }
            if ("content.xml" !in written) {
                writeEntry(zipOut, "content.xml", contentXml.toByteArray(Charsets.UTF_8)); written.add("content.xml")
            }
            if ("meta.xml" !in written) {
                writeEntry(zipOut, "meta.xml", metaXml.toByteArray(Charsets.UTF_8)); written.add("meta.xml")
            }
            // Minimal styles.xml when the package didn't already carry one. (Priority 1)
            if ("styles.xml" !in written) {
                writeEntry(zipOut, "styles.xml", OdfSerializer.serializeStyles(document).toByteArray(Charsets.UTF_8)); written.add("styles.xml")
            }
            // Freeze-pane settings for a source that had no settings.xml. (C2)
            if (settingsXml != null && "settings.xml" !in written) {
                writeEntry(zipOut, "settings.xml", settingsXml.toByteArray(Charsets.UTF_8)); written.add("settings.xml")
            }
            // Document images (inserted via the editor) not already in the package. (A6)
            for ((path, bytes) in docImages) {
                if (path == "inline" || path.isBlank() || path in written || bytes.isEmpty()) continue
                writeEntry(zipOut, path, bytes); written.add(path)
                extraManifest[path] = mediaTypeFor(path, document)
            }
            // Inline images promoted to the package during serialization. (A6)
            for ((path, bytes) in result.images) {
                if (path in written || bytes.isEmpty()) continue
                writeEntry(zipOut, path, bytes); written.add(path)
            }
            // Embedded chart objects. (A8)
            for ((path, xml) in result.objects) {
                if (path in written) continue
                writeEntry(zipOut, path, xml.toByteArray(Charsets.UTF_8)); written.add(path)
            }
            // Regenerate META-INF/manifest.xml listing everything actually in the package.
            val manifestXml = buildManifest(document, written, extraManifest)
            writeEntry(zipOut, "META-INF/manifest.xml", manifestXml.toByteArray(Charsets.UTF_8))
        }

        context.contentResolver.openOutputStream(targetUri, "wt")?.use { output ->
            buffer.writeTo(output)
        }
    }

    private fun writeEntry(zipOut: ZipOutputStream, name: String, bytes: ByteArray) {
        zipOut.putNextEntry(ZipEntry(name))
        zipOut.write(bytes)
        zipOut.closeEntry()
    }

    /** Writes an uncompressed (STORED) zip entry — required for the ODF `mimetype` member. */
    private fun writeStored(zipOut: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        val crc = java.util.zip.CRC32(); crc.update(bytes); entry.crc = crc.value
        zipOut.putNextEntry(entry)
        zipOut.write(bytes)
        zipOut.closeEntry()
    }

    /** Patches page geometry attributes into the first style:page-layout-properties of styles.xml. (Priority 7) */
    private fun patchStylesXml(xml: String, ps: OdfPageSetup): String {
        val m = Regex("<style:page-layout-properties\\b[^>]*?(/?)>").find(xml) ?: return xml
        var tag = m.value
        fun cm(px: Float): String = String.format(java.util.Locale.US, "%.4fcm", px / 37.795f)
        fun setAttr(name: String, value: String) {
            val attr = "$name=\"$value\""
            val r = Regex(Regex.escape(name) + "=\"[^\"]*\"")
            tag = if (r.containsMatchIn(tag)) r.replace(tag) { attr }
            else if (tag.endsWith("/>")) tag.dropLast(2) + " $attr/>"
            else tag.dropLast(1) + " $attr>"
        }
        setAttr("fo:page-width", cm(ps.widthPx))
        setAttr("fo:page-height", cm(ps.heightPx))
        setAttr("fo:margin-left", cm(ps.marginLeftPx))
        setAttr("fo:margin-right", cm(ps.marginRightPx))
        setAttr("fo:margin-top", cm(ps.marginTopPx))
        setAttr("fo:margin-bottom", cm(ps.marginBottomPx))
        setAttr("style:print-orientation", if (ps.isLandscape) "landscape" else "portrait")
        return xml.replaceRange(m.range, tag)
    }

    /** Rebuilds META-INF/manifest.xml from the final package contents so new images/objects are declared. (A6/A8) */
    private fun buildManifest(document: OdfDocument, written: Set<String>, extra: Map<String, String>): String {
        val mime = documentMime(document)
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.append("""<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.3">""")
        sb.append("""<manifest:file-entry manifest:full-path="/" manifest:version="1.3" manifest:media-type="$mime"/>""")
        // Embedded-object directory entries (declared first so readers register the sub-documents).
        for ((path, type) in extra) {
            if (path.endsWith("/")) sb.append("""<manifest:file-entry manifest:full-path="$path" manifest:media-type="$type"/>""")
        }
        val seen = mutableSetOf("/")
        fun add(path: String, type: String) {
            if (path in seen) return
            seen.add(path)
            sb.append("""<manifest:file-entry manifest:full-path="$path" manifest:media-type="$type"/>""")
        }
        for ((path, type) in extra) if (!path.endsWith("/")) add(path, type)
        for (path in written) {
            if (path == "mimetype" || path == "META-INF/manifest.xml") continue
            add(path, mediaTypeFor(path, document))
        }
        sb.append("</manifest:manifest>")
        return sb.toString()
    }

    private fun documentMime(document: OdfDocument): String = when (document) {
        is OdfDocument.TextDocument -> "application/vnd.oasis.opendocument.text"
        is OdfDocument.Spreadsheet -> "application/vnd.oasis.opendocument.spreadsheet"
        is OdfDocument.Presentation -> "application/vnd.oasis.opendocument.presentation"
        is OdfDocument.Drawing -> "application/vnd.oasis.opendocument.graphics"
    }

    private fun mediaTypeFor(path: String, document: OdfDocument): String = when {
        path == "content.xml" || path == "styles.xml" || path == "meta.xml" || path == "settings.xml" -> "text/xml"
        path.endsWith("/content.xml") || path.endsWith("/styles.xml") -> "text/xml"
        path.endsWith(".xml") -> "text/xml"
        path.endsWith(".rdf") -> "application/rdf+xml"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".gif") -> "image/gif"
        path.endsWith(".bmp") -> "image/bmp"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".webp") -> "image/webp"
        else -> "application/octet-stream"
    }

    private fun imagesOf(document: OdfDocument): Map<String, ByteArray> = when (document) {
        is OdfDocument.TextDocument -> document.images
        is OdfDocument.Spreadsheet -> document.images
        is OdfDocument.Presentation -> document.images
        is OdfDocument.Drawing -> document.images
    }

    fun saveAs(context: Context, sourceUri: Uri?, document: OdfDocument, targetUri: Uri) {
        save(context, sourceUri, document, targetUri)
    }
}
