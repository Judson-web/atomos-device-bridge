package dev.atomos.bridge

/**
 * Validates a single user-visible file or directory name.
 * Rejects path separators, traversal tokens, drive delimiters and control characters.
 */
internal fun isSafeFileName(value: String): Boolean =
    value.isNotBlank() &&
        value != "." &&
        value != ".." &&
        value.length <= 255 &&
        value.none {
            it == '/' || it == '\\' || it == ':' || Character.isISOControl(it)
        }

/** Converts an archive path to safe, normalized path segments before extraction. */
internal fun normalizeArchiveEntryPath(entryName: String): List<String> {
    val normalized = entryName.replace('\\', '/')
    require(normalized.isNotBlank() && normalized.length <= 4096) {
        "Unsafe ZIP entry path rejected."
    }
    require(!normalized.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
        "Absolute ZIP entry paths are not allowed."
    }
    val parts = normalized.split('/').filter { it.isNotEmpty() }
    require(parts.isNotEmpty() && parts.all(::isSafeFileName)) {
        "Unsafe ZIP entry path rejected."
    }
    return parts
}

/** Converts arbitrary provider names to a harmless ZIP path component. */
internal fun safeArchiveSegment(value: String): String {
    val cleaned = value
        .replace('/', '_')
        .replace('\\', '_')
        .replace(':', '_')
        .filterNot { Character.isISOControl(it) }
        .trim()
        .take(255)
    return cleaned.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "unnamed"
}
