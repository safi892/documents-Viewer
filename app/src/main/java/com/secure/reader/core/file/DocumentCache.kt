package com.secure.reader.core.file

import java.io.File

/** Where seekable on-device copies of opened documents live, under filesDir. */
object DocumentCache {
    const val DIR = "docs"

    fun directory(filesDir: File): File = File(filesDir, DIR)

    /** Cached copy for a content hash: docs/<sha256>.<ext> */
    fun fileFor(filesDir: File, id: String, extension: String): File =
        File(directory(filesDir), "$id.$extension")

    /**
     * The cached copy of a document, named by its content hash and by the
     * extension the file itself carries where its format claims that extension:
     * `notes.md` stays `notes.md`, `data.csv` stays `data.csv`. Only a suffix
     * the format does not claim falls back to the family's canonical extension —
     * a JSON body served under a name with no useful suffix becomes `.txt`.
     *
     * The write side and the read side must both come through here. They used to
     * derive this separately — the copy was *written* as `<hash>.md` and then
     * *looked for* as `<hash>.txt` — so every re-open of a Markdown, JSON, XML,
     * RTF, CSV or legacy `.doc` file failed with "The file could not be read".
     */
    fun cachedFileFor(filesDir: File, id: String, fileName: String, format: Format): File =
        fileFor(filesDir, id, extensionFor(fileName, format))

    /** The suffix a file is cached under: its own, when the format claims it. */
    private fun extensionFor(fileName: String, format: Format): String {
        val safe = Format.extensionOf(fileName)?.filter { it.isLetterOrDigit() }
        return when {
            safe.isNullOrEmpty() -> format.canonicalExtension
            safe in format.extensions -> safe
            else -> format.canonicalExtension
        }
    }
}
