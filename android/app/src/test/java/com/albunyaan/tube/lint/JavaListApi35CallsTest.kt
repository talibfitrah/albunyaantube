package com.albunyaan.tube.lint

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * With compileSdk >= 35, `list.removeFirst()` / `removeLast()` / `getFirst()` / `getLast()` on a
 * MutableList/ArrayList compile to the Java 21 `java.util.List` methods (API 35), which throw
 * NoSuchMethodError on Android 14 and below. Lint's NewApi reports them, but the lint baseline and
 * `abortOnError = false` let them ship (Play pre-review flagged SearchFragment + PlayerViewModel).
 *
 * Limits (textual scan): only `name.method()` / `name?.` / `name!!.` calls, judged by the declared
 * type (else initializer) of the first `val`/`var name` in the same file; a Deque receiver (kotlin/java ArrayDeque, LinkedList — members
 * since API 1/9) is allowed.
 * It does not see chained receivers or the `.first` / `.last` property syntax.
 */
class JavaListApi35CallsTest {

    @Test
    fun `no Java 21 List first-last calls in app sources`() {
        val call = Regex("""\b(\w+)(?:\?|!!)?\.(removeFirst|removeLast|getFirst|getLast)\(\)""")
        val sources = File("src").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.split('/')[1].let { set -> set.startsWith("test") || set.startsWith("androidTest") } }
            .toList()
        assertTrue("scanned no app sources (run from android/app)", sources.any { it.name == "SearchFragment.kt" })
        val offenders = sources.asSequence()
            .flatMap { file ->
                val text = file.readText()
                call.findAll(text).filterNot { m ->
                    // Declared type if written, else the initializer: `val x: MutableList<T> = ArrayDeque()` is a List.
                    val decl = Regex("""(?:val|var)\s+${m.groupValues[1]}\b\s*(?::\s*([^=\n]+))?(?:=\s*([^\n]*))?""").find(text)
                    val type = decl?.groupValues?.get(1)?.ifBlank { null } ?: decl?.groupValues?.get(2).orEmpty()
                    "Deque" in type || "LinkedList" in type
                }.map { m -> "${file.path}:${text.substring(0, m.range.first).count { it == '\n' } + 1} ${m.value}" }
            }
            .toList()
        assertTrue(
            "Use removeAt(0) / removeAt(lastIndex) / [0] / [lastIndex] instead:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
