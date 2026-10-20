package com.secure.reader.core.file

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentCacheTest {

    private val filesDir: File = Files.createTempDirectory("reader-cache-test").toFile()

    /**
     * The regression this file exists for: the copy was *written* as
     * `<hash>.md` while `reopen` *looked for* `<hash>.txt`, so tapping a
     * Markdown, JSON, XML, RTF, CSV or legacy `.doc` document in the library
     * failed with "The file could not be read".
     */
    @Test
    fun `does not fall back to the canonical extension for a claimed one`() {
        assertNotEquals(
            "abc123.txt",
            DocumentCache.cachedFileFor(filesDir, "abc123", "notes.md", Format.TEXT).name,
        )
    }

    @Test
    fun `keeps the extension the format claims`() {
        assertEquals(
            "abc123.md",
            DocumentCache.cachedFileFor(filesDir, "abc123", "notes.md", Format.TEXT).name,
        )
        assertEquals(
            "abc123.json",
            DocumentCache.cachedFileFor(filesDir, "abc123", "data.json", Format.TEXT).name,
        )
        assertEquals(
            "abc123.csv",
            DocumentCache.cachedFileFor(filesDir, "abc123", "rows.csv", Format.SHEET).name,
        )
        assertEquals(
            "abc123.doc",
            DocumentCache.cachedFileFor(filesDir, "abc123", "letter.doc", Format.WORD).name,
        )
    }

    /**
     * A file opened once has to be found again. Both sides now go through the
     * same call, which is what makes this true by construction.
     */
    @Test
    fun `an opened file is found again under the same name`() {
        // The repository creates this before it writes; the name is what is
        // under test here.
        DocumentCache.directory(filesDir).mkdirs()
        val written = DocumentCache.cachedFileFor(filesDir, "abc123", "notes.md", Format.TEXT)
        written.writeText("# hello")
        val read = DocumentCache.cachedFileFor(filesDir, "abc123", "notes.md", Format.TEXT)
        assertEquals(written, read)
        assertTrue(read.exists())
    }

    @Test
    fun `falls back to the canonical extension when the format does not claim it`() {
        // A JSON body reaches the text family through its mime type while the
        // name carries no usable suffix, so the family's own extension is used.
        assertEquals(
            "abc123.txt",
            DocumentCache.cachedFileFor(filesDir, "abc123", "payload.bak", Format.TEXT).name,
        )
        assertEquals(
            "abc123.txt",
            DocumentCache.cachedFileFor(filesDir, "abc123", "README", Format.TEXT).name,
        )
    }
}
