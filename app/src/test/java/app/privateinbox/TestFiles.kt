package app.privateinbox

import java.io.File

/** Resolves project files whether Gradle runs tests from the module or the root directory. */
object TestFiles {
    fun module(path: String): File =
        listOf(File(path), File("app/$path")).firstOrNull { it.exists() }
            ?: error("not found: $path (cwd=${File(".").absolutePath})")

    fun mainSources(): List<File> =
        module("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
}
