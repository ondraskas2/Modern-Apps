package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.*

/**
 * Entry point for importing Microsoft OOXML packages (.docx / .xlsx / .pptx). Reads the package
 * once (retaining media bytes) and delegates to the per-format importers ([OoxmlDocx], [OoxmlXlsx],
 * [OoxmlPptx]). Best-effort: maps a wide range of OOXML features onto the ODF model, degrading
 * gracefully where the model can't represent something.
 */
object OoxmlImporter {

    /** Imports OOXML [bytes]; returns null if the package isn't a recognized docx/xlsx/pptx. */
    fun import(bytes: ByteArray, fileName: String): OdfDocument? {
        require(!isEncryptedOfficeFile(bytes)) { "This Office file is password-protected. Remove the password and try again." }
        val pkg = OoxmlPackage.read(bytes)
        require(!pkg.entries.containsKey("EncryptionInfo")) { "This Office file is password-protected. Remove the password and try again." }
        val entries = pkg.entries
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return runCatching {
            when {
                ext in DOCX_EXTS || entries.containsKey("word/document.xml") -> OoxmlDocx.import(pkg, fileName)
                ext in XLSX_EXTS || entries.containsKey("xl/workbook.xml") -> OoxmlXlsx.import(pkg, fileName)
                ext in PPTX_EXTS || entries.keys.any { it.startsWith("ppt/slides/slide") } -> OoxmlPptx.import(pkg, fileName)
                else -> null
            }
        }.getOrElse {
            // Best-effort: never crash the app on a malformed but recognized package.
            when {
                ext in DOCX_EXTS || entries.containsKey("word/document.xml") -> OdfDocument.TextDocument(fileName, emptyList())
                ext in XLSX_EXTS || entries.containsKey("xl/workbook.xml") -> OdfDocument.Spreadsheet(fileName, listOf())
                ext in PPTX_EXTS || entries.keys.any { it.startsWith("ppt/slides/slide") } -> OdfDocument.Presentation(fileName, listOf())
                else -> null
            }
        }
    }

    /** True if [bytes] is a ZIP whose entries look like an OOXML package. */
    fun looksLikeOoxml(bytes: ByteArray): Boolean {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return false
        val names = OoxmlPackage.read(bytes).entries.keys
        return names.any { it.startsWith("word/") || it.startsWith("xl/") || it.startsWith("ppt/") }
    }

    /** True if [bytes] is an OLE/CFB compound file — the container used by password-protected Office files. */
    fun isEncryptedOfficeFile(bytes: ByteArray): Boolean =
        bytes.size >= 8 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() && bytes[3] == 0xE0.toByte()

    private val DOCX_EXTS = setOf("docx", "docm", "dotx", "dotm")
    private val XLSX_EXTS = setOf("xlsx", "xlsm", "xltx", "xltm")
    private val PPTX_EXTS = setOf("pptx", "pptm", "potx", "potm", "ppsx", "ppsm")
}
