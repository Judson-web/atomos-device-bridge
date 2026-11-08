package dev.atomos.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSafetyTest {
    @Test
    fun acceptsOrdinaryAndUnicodeNames() {
        assertTrue(isSafeFileName("Camera Uploads"))
        assertTrue(isSafeFileName("छवि-2026.png"))
        assertTrue(isSafeFileName("archive.final.zip"))
    }

    @Test
    fun rejectsTraversalAndControlCharacters() {
        assertFalse(isSafeFileName("."))
        assertFalse(isSafeFileName(".."))
        assertFalse(isSafeFileName("../escape.txt"))
        assertFalse(isSafeFileName("folder\\\\escape.txt"))
        assertFalse(isSafeFileName("bad:name"))
        assertFalse(isSafeFileName("line\nfeed"))
    }

    @Test
    fun normalizesSafeNestedArchivePaths() {
        assertEquals(listOf("Pictures", "sample.jpg"), normalizeArchiveEntryPath("Pictures/sample.jpg"))
        assertEquals(listOf("Documents", "notes.txt"), normalizeArchiveEntryPath("Documents\\\\notes.txt"))
        assertEquals(listOf("Pictures"), normalizeArchiveEntryPath("Pictures/"))
    }

    @Test
    fun rejectsAbsoluteAndTraversalArchivePaths() {
        assertThrows(IllegalArgumentException::class.java) { normalizeArchiveEntryPath("../outside.txt") }
        assertThrows(IllegalArgumentException::class.java) { normalizeArchiveEntryPath("/absolute/file.txt") }
        assertThrows(IllegalArgumentException::class.java) { normalizeArchiveEntryPath("C:\\\\Windows\\\\system.ini") }
        assertThrows(IllegalArgumentException::class.java) { normalizeArchiveEntryPath("folder/../../outside.txt") }
    }

    @Test
    fun sanitizesArchiveSegments() {
        assertEquals("camera_roll", safeArchiveSegment("camera/roll"))
        assertEquals("unnamed", safeArchiveSegment(".."))
    }
}
