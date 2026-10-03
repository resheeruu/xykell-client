package dev.xykell.client

/**
 * JNI facade over the builtin theme registry (Batch 10). Read-only:
 * theme names and their serialized semantic token sets. Selection and
 * persistence go through the validated settings bridge (client.theme),
 * never through this facade. All calls exception-safe.
 */
object NativeThemes {
    init {
        System.loadLibrary("xykellcore")
    }

    external fun listThemes(): Array<String>
    external fun themeTokens(name: String): String?

    /** Builtin names in registry order; empty when the bridge is missing. */
    fun names(): List<String> = guard { listThemes().toList() } ?: emptyList()

    /** Serialized tokens for a builtin; null for unknown names or failure. */
    fun tokens(name: String): String? = guard { themeTokens(name) }

    private inline fun <T> guard(block: () -> T): T? {
        return try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }
}
