package dev.xykell.client.runtime.packs

import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Reads a `.mcpack` container without extracting it to disk.
 *
 * **Context.** A `.mcpack` is a zip holding `manifest.json` plus the pack's
 * resources. The app already accepts one through the system picker
 * (`MainActivity`, `OPEN_DOCUMENT`) and already exports through a
 * `FileProvider`, so this type completes that path: it reads a container the
 * user chose, hands [PackStore] the manifest, and never writes anything.
 *
 * **Why not just unzip it.** Because a zip from outside the app is untrusted
 * input with two well-known failure modes, and a phone is the wrong place to
 * meet either:
 *
 *  - **Decompression bomb.** A tiny archive can declare a member that expands
 *    to gigabytes. Reading it entry-by-entry with a running total turns that
 *    from an out-of-memory kill into a clean refusal, and the refusal names the
 *    limit it hit instead of just dying.
 *  - **Entry-name traversal.** A member called `../../databases/app.db` is
 *    harmless as long as we never write it anywhere. We do not, so rather than
 *    silently trusting the names we *report* them: [hasTraversal] is a
 *    property of the container, surfaced so a caller that later adds extraction
 *    has to make a decision instead of inheriting one.
 *
 * Reading is capped on entry count, per-entry size and total decompressed size.
 * Every cap has a comment saying what it is for. Nothing here throws for bad
 * input: a malformed container is an expected outcome of accepting user files,
 * and it is reported as empty rather than propagated as an exception.
 *
 * Pure Kotlin: no Android imports, host-testable.
 */
object PackArchive {

    /** Cap on entries in one container. Real packs are well under this. */
    const val MAX_ENTRIES = 4096

    /**
     * Cap on total decompressed bytes across the whole container. Sized for a
     * large texture pack with room to spare; the point is that it exists, not
     * that it is exactly right.
     */
    const val MAX_TOTAL_BYTES = 192 * 1024 * 1024

    /** Cap on a single member. Guards against one entry claiming the world. */
    const val MAX_ENTRY_BYTES = 64 * 1024 * 1024

    /** Manifests are metadata. Anything larger is not a manifest. */
    const val MAX_MANIFEST_BYTES = 512 * 1024

    const val MANIFEST_NAME = "manifest.json"

    /** One member as seen inside the container. [uncompressedSize] may be -1. */
    data class Entry(
        val name: String,
        val uncompressedSize: Long,
        val isDirectory: Boolean,
    )

    /** What a scan found. [truncated] means the caps stopped the walk early. */
    data class Contents(
        val entries: List<Entry>,
        val manifest: Map<String, Any>,
        val totalBytes: Long,
        val truncated: Boolean,
        val hasTraversal: Boolean,
        val readable: Boolean,
    ) {
        val entryCount: Int get() = entries.size

        companion object {
            val EMPTY = Contents(emptyList(), emptyMap(), 0, false, false, false)
        }
    }

    /**
     * Walks [bytes] as a zip, collecting entry names and reading only the
     * manifest. Never extracts, never writes.
     *
     * A container that is not a zip yields [Contents.EMPTY] with
     * `readable = false` — "that file is not a pack" is an answer, not a crash.
     */
    fun scan(bytes: ByteArray?): Contents {
        if (bytes == null || bytes.isEmpty()) return Contents.EMPTY

        val entries = ArrayList<Entry>()
        var total = 0L
        var truncated = false
        var traversal = false
        var manifestBytes: ByteArray? = null
        var sawEntry = false

        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry: ZipEntry = zip.nextEntry ?: break
                    sawEntry = true
                    if (entries.size >= MAX_ENTRIES) {
                        truncated = true
                        break
                    }
                    val name = entry.name ?: ""
                    if (hasTraversalName(name)) traversal = true
                    entries.add(
                        Entry(
                            name = name,
                            uncompressedSize = entry.size,
                            isDirectory = entry.isDirectory,
                        ),
                    )

                    // Directory members carry no payload; charging them would
                    // invent bytes that do not exist.
                    if (!entry.isDirectory) {
                        val read = readBounded(zip, MAX_ENTRY_BYTES)
                        if (read == null) {
                            truncated = true
                            break
                        }
                        if (isManifest(name) && manifestBytes == null &&
                            read.size <= MAX_MANIFEST_BYTES
                        ) {
                            manifestBytes = read
                        }
                        total += read.size
                        if (total > MAX_TOTAL_BYTES) {
                            truncated = true
                            break
                        }
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            // Not a zip, or a corrupt one. Reported, not thrown.
            return Contents.EMPTY
        }

        if (!sawEntry) return Contents.EMPTY

        val manifest = manifestBytes?.let { PackStore.extractFromManifest(it) } ?: emptyMap()
        return Contents(
            entries = entries,
            manifest = manifest,
            totalBytes = total,
            truncated = truncated,
            hasTraversal = traversal,
            readable = true,
        )
    }

    /**
     * True when [contents] is complete enough to offer to the user: it read as a
     * zip, it has a manifest, and it did not hit a cap. A container that hit a
     * cap is offered as "damaged", never as a working pack.
     */
    fun isUsablePack(contents: Contents): Boolean =
        contents.readable && !contents.truncated && contents.manifest.isNotEmpty()

    /** Names under a prefix, e.g. `resources/`. Bounded by construction. */
    fun namesUnder(contents: Contents, prefix: String): List<String> =
        contents.entries.filter { it.name.startsWith(prefix) && !it.isDirectory }.map { it.name }

    private fun isManifest(name: String): Boolean =
        name.substringAfterLast('/') == MANIFEST_NAME

    /**
     * Reads at most [cap] bytes, returning null when the stream is longer.
     * Reading one byte past the cap is what distinguishes "exactly at the cap"
     * from "over it", so the check is not off by one.
     */
    private fun readBounded(zip: ZipInputStream, cap: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Zip-slip check: an absolute path, a Windows drive prefix, or a `..`
     * segment that escapes the archive root. Purely a report — see class docs.
     */
    fun hasTraversalName(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name.startsWith("/") || name.startsWith("\\")) return true
        if (name.length >= 2 && name[1] == ':') return true
        return name.split('/', '\\').any { it == ".." }
    }
}