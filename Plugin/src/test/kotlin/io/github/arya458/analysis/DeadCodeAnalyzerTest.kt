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

    @Test
    fun `external framework overrides are not reported dead`() {
        val projectDirectory = Files.createTempDirectory("dead-code-analyzer-test").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
            val paintComponent = MethodRef(
                "sample/StatusDot",
                "paintComponent",
                "(Ljava/awt/Graphics;)V",
                access = 4
            )
            val analysis = DeadCodeAnalyzer(project, DeadCodeDetectorExtension()).analyze(
                ClassScanModel(
                    declaredMethods = setOf(paintComponent),
                    declaredFields = emptySet(),
                    declaredClasses = setOf("sample/StatusDot"),
                    classAnnotations = emptyMap(),
                    referencedMethods = emptySet(),
                    referencedFields = emptySet(),
                    referencedClasses = setOf("javax/swing/JComponent"),
                    classParents = mapOf("sample/StatusDot" to setOf("javax/swing/JComponent"))
                ),
                ResourceModel(emptySet(), emptySet())
            )

            assertTrue(paintComponent !in analysis.deadMethods)
        } finally {
            projectDirectory.deleteRecursively()
        }
    }

    @Test
    fun `compile time constants and serialization ids are not reported dead`() {
        val projectDirectory = Files.createTempDirectory("dead-code-analyzer-test").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
            val constant = FieldRef(
                "sample/Constants",
                "TITLE",
                "Ljava/lang/String;",
                access = java.lang.reflect.Modifier.PRIVATE or
                    java.lang.reflect.Modifier.STATIC or
                    java.lang.reflect.Modifier.FINAL,
                compileTimeConstant = true
            )
            val serialVersionUid = FieldRef(
                "sample/SerializableValue",
                "serialVersionUID",
                "J",
                access = java.lang.reflect.Modifier.PRIVATE or java.lang.reflect.Modifier.STATIC
            )
            val ordinaryField = FieldRef("sample/Unused", "value", "Ljava/lang/Object;")
            val analysis = DeadCodeAnalyzer(project, DeadCodeDetectorExtension()).analyze(
                ClassScanModel(
                    declaredMethods = emptySet(),
                    declaredFields = setOf(constant, serialVersionUid, ordinaryField),
                    declaredClasses = emptySet(),
                    classAnnotations = emptyMap(),
                    referencedMethods = emptySet(),
                    referencedFields = emptySet(),
                    referencedClasses = emptySet()
                ),
                ResourceModel(emptySet(), emptySet())
            )

            assertTrue(ordinaryField in analysis.deadFields)
            assertTrue(constant !in analysis.deadFields)
            assertTrue(serialVersionUid !in analysis.deadFields)
        } finally {
            projectDirectory.deleteRecursively()
        }
    }
}
