package com.secure.reader.core.file

/**
 * Which rendering pipeline handles a document.
 *
 * - [PDF] is rasterized natively with [android.graphics.pdf.PdfRenderer].
 * - [IMAGE] is decoded natively with the platform bitmap codecs.
 * - [OFFICE_WEB] is rendered by the bundled open-source JS engines inside a
 *   sandboxed WebView served over a WebViewAssetLoader virtual origin.
 */
enum class Engine {
    PDF,
    IMAGE,
    OFFICE_WEB,
}

/**
 * Every format the reader opens, with the routing data needed to pick an engine.
 *
 * The set is deliberately honest: a family is listed here only once it has a
 * working renderer in the current build, because the fastest way to earn a
 * one-star review is to advertise a format that opens into an error.
 */
enum class Format(
    val engine: Engine,
    /** Short display tag, e.g. "DOCX". Shown in mono in the UI. */
    val tag: String,
    /** Canonical extension used for the cached on-device copy. */
    val canonicalExtension: String,
    val extensions: Set<String>,
    val mimeTypes: Set<String>,
) {
    PDF(
        engine = Engine.PDF,
        tag = "PDF",
        canonicalExtension = "pdf",
        extensions = setOf("pdf"),
        mimeTypes = setOf("application/pdf"),
    ),

    /** Raster images: photos, scans and screenshots read as single pages. */
    IMAGE(
        engine = Engine.IMAGE,
        tag = "IMG",
        canonicalExtension = "jpg",
        extensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif"),
        mimeTypes = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif",
            "image/bmp",
            "image/x-ms-bmp",
            "image/avif",
            "image/heic",
            "image/heif",
        ),
    ),

    /** Reflowable books: EPUB 2 and EPUB 3 (a zip of XHTML, parsed in-page). */
    EPUB(
        engine = Engine.OFFICE_WEB,
        tag = "EPUB",
        canonicalExtension = "epub",
        extensions = setOf("epub"),
        mimeTypes = setOf("application/epub+zip"),
    ),

    /** Plain-text family: anything that reads as characters, formatted as characters. */
    TEXT(
        engine = Engine.OFFICE_WEB,
        tag = "TXT",
        canonicalExtension = "txt",
        extensions = setOf(
            "txt", "text", "log", "md", "markdown", "rtf",
            "html", "htm", "xhtml", "xml", "json",
            "yaml", "yml", "ini", "cfg", "conf", "properties",
            "srt", "vtt",
        ),
        mimeTypes = setOf(
            "text/plain",
            "text/markdown",
            "text/x-markdown",
            "application/rtf",
            "text/rtf",
            "text/html",
            "application/xhtml+xml",
            "text/xml",
            "application/xml",
            "application/json",
            "application/x-yaml",
            "text/yaml",
            "text/x-json",
            "application/x-subrip",
            "text/vtt",
        ),
    ),

    /**
     * Comic archives. CBZ only: it is a zip of images and both render engines
     * already carry a zip reader. CBR (RAR) is deliberately absent — claiming a
     * decompressor we do not ship is exactly the overpromise users call out.
     */
    COMIC(
        engine = Engine.OFFICE_WEB,
        tag = "CBZ",
        canonicalExtension = "cbz",
        extensions = setOf("cbz"),
        mimeTypes = setOf(
            "application/vnd.comicbook+zip",
            "application/x-cbz",
        ),
    ),

    WORD(
        engine = Engine.OFFICE_WEB,
        tag = "DOCX",
        canonicalExtension = "docx",
        // .doc (OLE2 binary) is read by the legacy extractor, not docx-preview.
        extensions = setOf("docx", "docm", "dotx", "dotm", "doc"),
        mimeTypes = setOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-word.document.macroEnabled.12",
            "application/msword",
            "application/x-msword",
        ),
    ),

    SHEET(
        engine = Engine.OFFICE_WEB,
        tag = "XLSX",
        canonicalExtension = "xlsx",
        extensions = setOf("xlsx", "xlsm", "xlsb", "xls", "csv", "tsv"),
        mimeTypes = setOf(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel.sheet.macroEnabled.12",
            "application/vnd.ms-excel",
            "application/msexcel",
            "application/x-msexcel",
            "text/csv",
            "text/tab-separated-values",
        ),
    ),

    SLIDES(
        engine = Engine.OFFICE_WEB,
        tag = "PPTX",
        canonicalExtension = "pptx",
        // Legacy .ppt (OLE2 binary) is deliberately absent: reading its text
        // out of the PowerPoint record stream is not something this build can
        // do reliably, and a format that opens into an error earns one stars.
        extensions = setOf("pptx", "ppsx", "pptm", "ppsm", "potx"),
        mimeTypes = setOf(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
            "application/vnd.ms-powerpoint.presentation.macroEnabled.12",
        ),
    ),

    ODF(
        engine = Engine.OFFICE_WEB,
        tag = "ODF",
        canonicalExtension = "odt",
        extensions = setOf("odt", "ods", "odp", "odg"),
        mimeTypes = setOf(
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation",
            "application/vnd.oasis.opendocument.graphics",
        ),
    ),
    ;

    /** Key understood by the bundled JS harness (renderers/harness.js). */
    val harnessKey: String
        get() = when (this) {
            PDF -> "pdf"
            IMAGE -> "image"
            EPUB -> "epub"
            TEXT -> "text"
            COMIC -> "comic"
            WORD -> "docx"
            SHEET -> "sheet"
            SLIDES -> "slides"
            ODF -> "odf"
        }

    /**
     * The tag to show for a specific file: "PNG" reads truer than "IMG", and
     * "MD" truer than "TXT". Long or unknown extensions fall back to the family
     * tag so the glyph box never overflows.
     */
    fun displayTag(fileName: String): String {
        val extension = extensionOf(fileName)?.uppercase() ?: return tag
        return if (extension.length in 1..4) extension else tag
    }

    companion object {
        /** Extension first (most reliable from the picker), mime as fallback. */
        fun resolve(mimeType: String?, fileName: String): Format? {
            val extension = extensionOf(fileName)
            if (extension != null) {
                entries.firstOrNull { extension in it.extensions }?.let { return it }
            }
            val mime = mimeType?.substringBefore(';')?.trim()?.lowercase()
            if (!mime.isNullOrEmpty()) {
                entries.firstOrNull { mime in it.mimeTypes }?.let { return it }
            }
            return null
        }

        fun extensionOf(fileName: String): String? =
            fileName.substringAfterLast('.', "").lowercase().ifEmpty { null }
    }
}
