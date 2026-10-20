package io.github.arya458.analysis

import java.io.File
import java.nio.file.Files
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertTrue
import org.gradle.testfixtures.ProjectBuilder

class ClassScannerTest {
    @Test
    fun `lambda and method reference implementation methods are referenced`() {
        val projectDirectory = Files.createTempDirectory("dead-code-scanner-test").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
            project.pluginManager.apply("java")
            val classesDirectory = project.layout.buildDirectory.dir("classes/java/main").get().asFile
            classesDirectory.mkdirs()
            compileSample(classesDirectory)

            val scan = ClassScanner(project, parallel = false, enableCaching = false).scan(false)

            assertTrue(
                scan.referencedMethods.any {
                    it.owner == "java/lang/invoke/LambdaMetafactory"
                            && it.name == "metafactory"
                }, scan.referencedMethods.joinToString())
            assertTrue(
                scan.referencedMethods.any {
                    it.owner == "sample/LambdaSample" && it.name == "methodReferenceTarget"
                })
            assertTrue(
                scan.referencedMethods.any {
                    it.owner == "sample/LambdaSample" && it.name == "lambdaTarget"
                })
        } finally {
            projectDirectory.deleteRecursively()
        }
    }

    private fun compileSample(outputDirectory: File) {
        val sourceDirectory = Files.createTempDirectory("dead-code-source-test").toFile()
        try {
            val sourceFile = File(sourceDirectory, "LambdaSample.java")
            sourceFile.parentFile.mkdirs()
            sourceFile.writeText(
                """
                package sample;

                import java.util.function.Consumer;

                public class LambdaSample {
                    public static void run() {
                        Consumer<String> lambda = value -> lambdaTarget(value);
                        Consumer<String> methodReference = LambdaSample::methodReferenceTarget;
                        lambda.accept("lambda");
                        methodReference.accept("method");
                    }

                    private static void lambdaTarget(String value) {}

                    private static void methodReferenceTarget(String value) {}
                }
                """.trimIndent())
            val compiler = ToolProvider.getSystemJavaCompiler()
                ?: error("A JDK compiler is required for this test")
            check(
                compiler.run(
                    null,
                    null,
                    null,
                    "--release",
                    "24",
                    "-d",
                    outputDirectory.absolutePath,
                    sourceFile.absolutePath
                ) == 0
            ) {
                "Could not compile scanner fixture"
            }
        } finally {
            sourceDirectory.deleteRecursively()
        }
    }
}
