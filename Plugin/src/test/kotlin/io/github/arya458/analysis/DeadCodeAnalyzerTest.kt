package io.github.arya458.analysis

import io.github.arya458.DeadCodeDetectorExtension
import io.github.arya458.model.ClassScanModel
import io.github.arya458.model.ResourceModel
import io.github.arya458.model.ref.FieldRef
import io.github.arya458.model.ref.MethodRef
import java.nio.file.Files
import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertTrue
import org.gradle.testfixtures.ProjectBuilder

class DeadCodeAnalyzerTest {
    @Test
    fun `class exclusions support patterns regex and legacy strings`() {
        val projectDirectory = Files.createTempDirectory("dead-code-analyzer-test").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
            val extension = DeadCodeDetectorExtension()
            extension.excludeClasses.add(Pattern.compile(".*package-info"))
            extension.excludeClasses.add(Regex(".*Generated"))
            extension.excludeClasses.add("sample.Legacy")

            val analysis = DeadCodeAnalyzer(project, extension).analyze(
                ClassScanModel(
                    declaredMethods = emptySet(),
                    declaredFields = emptySet(),
                    declaredClasses = setOf(
                        "sample/package-info",
                        "sample/Generated",
                        "sample/Legacy",
                        "sample/Dead"
                    ),
                    classAnnotations = emptyMap(),
                    referencedMethods = emptySet(),
                    referencedFields = emptySet(),
                    referencedClasses = emptySet()
                ),
                ResourceModel(emptySet(), emptySet())
            )

            assertTrue(analysis.deadClasses.none { it in setOf("sample/package-info", "sample/Generated", "sample/Legacy") })
            assertTrue(analysis.deadClasses.any { it == "sample/Dead" })
        } finally {
            projectDirectory.deleteRecursively()
        }
    }

    @Test
    fun `references to abstract methods keep concrete overrides`() {
        val projectDirectory = Files.createTempDirectory("dead-code-analyzer-test").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
            val baseMethod = MethodRef("sample/Base", "work", "()V")
            val override = MethodRef("sample/Implementation", "work", "()V")
            val analysis = DeadCodeAnalyzer(project, DeadCodeDetectorExtension()).analyze(
                ClassScanModel(
                    declaredMethods = setOf(baseMethod, override),
                    declaredFields = emptySet(),
                    declaredClasses = setOf("sample/Base", "sample/Implementation"),
                    classAnnotations = emptyMap(),
                    referencedMethods = setOf(baseMethod),
                    referencedFields = emptySet(),
                    referencedClasses = setOf("sample/Base"),
                    classParents = mapOf("sample/Implementation" to setOf("sample/Base"))
                ),
                ResourceModel(emptySet(), emptySet())
            )

            assertTrue(analysis.deadMethods.none { it == override })

            val baseCallAnalysis = DeadCodeAnalyzer(project, DeadCodeDetectorExtension()).analyze(
                ClassScanModel(
                    declaredMethods = setOf(baseMethod),
                    declaredFields = emptySet(),
                    declaredClasses = setOf("sample/Base", "sample/Implementation"),
                    classAnnotations = emptyMap(),
                    referencedMethods = setOf(override),
                    referencedFields = emptySet(),
                    referencedClasses = emptySet(),
                    classParents = mapOf("sample/Implementation" to setOf("sample/Base"))
                ),
                ResourceModel(emptySet(), emptySet())
            )

            assertTrue(baseCallAnalysis.deadMethods.none { it == baseMethod })
        } finally {
            projectDirectory.deleteRecursively()
        }
    }
}
