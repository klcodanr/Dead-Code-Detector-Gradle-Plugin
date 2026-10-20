package io.github.arya458.analysis

import io.github.arya458.DeadCodeDetectorExtension
import io.github.arya458.analysis.platform.*
import io.github.arya458.model.ClassScanModel
import io.github.arya458.model.ResourceModel
import io.github.arya458.model.DeadCodeModel
import io.github.arya458.model.ref.FieldRef
import io.github.arya458.model.ref.MethodRef
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.gradle.api.Project
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.util.regex.Pattern

class DeadCodeAnalyzer(
    private val project: Project,
    private val extension: DeadCodeDetectorExtension
) {

    private val platformRules: PlatformKeepRules by lazy {
        detectPlatformRules()
    }

    private val externalOverrideCache = mutableMapOf<MethodRef, Boolean>()
    private val projectClassLoader: ClassLoader by lazy { createProjectClassLoader() }

    private val kotlinGeneratedMethodPatterns = listOf(
        Regex("component\\d+"),
        Regex("copy"),
        Regex("toString"),
        Regex("hashCode"),
        Regex("equals"),
        Regex("invoke"),
        Regex("get\\w+"),
        Regex("set\\w+")
    )

    fun analyze(classScan: ClassScanModel, resScan: ResourceModel): DeadCodeModel {
        val declaredMethods = classScan.declaredMethods
        val declaredFields = classScan.declaredFields
        val declaredClasses = classScan.declaredClasses
        val classAnnotations = classScan.classAnnotations
        val referencedFields = classScan.referencedFields
        val referencedClasses = classScan.referencedClasses

        val allReferencedMethods = referencedMethodsWithOverrides(classScan) + resScan.referencedMethodsFromXml

        val referencedResourcesFromFields = classScan.referencedFields.mapNotNull { field ->
            val owner = field.owner
            if (owner.contains("R$") && field.desc == "I") {
                val type = owner.substringAfterLast("R$")
                if (type.isNotEmpty()) type to field.name else null
            } else null
        }.toSet()

        val allReferencedResources = resScan.referenced + referencedResourcesFromFields

        val allReferencedClasses = referencedClasses +
                resScan.referencedClassesFromManifest +
                resScan.referencedClassesFromSpringConfig

        val deadMethods = declaredMethods.filter { method ->
            if (method.name in listOf("<init>", "<clinit>")) return@filter false
            if ((method.access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE)) != 0) return@filter false
            if (kotlinGeneratedMethodPatterns.any { it.matches(method.name) }) return@filter false
            if (method.name == "main" && (method.desc == "([Ljava/lang/String;)V" || method.desc == "()V")) return@filter false
            if (extension.keepPublicApi && (method.access and Opcodes.ACC_PUBLIC) != 0) return@filter false
            if (extension.excludeMethods.any { it.matcher(method.name).matches() }) return@filter false
            if (hasKeepAnnotation(method.annotations)) return@filter false
            if (platformRules.shouldKeepMethod(method, classAnnotations)) return@filter false
            if (extension.customKeepRules("method", classAnnotations, method)) return@filter false
            if (isExternalOverride(method, classScan)) return@filter false

            val directRef = allReferencedMethods.any { it.owner == method.owner && it.name == method.name && it.desc == method.desc }
            !directRef
        }

        val deadFields = declaredFields.filter { field ->
            if ((field.access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE)) != 0) return@filter false
            if (extension.excludeConstants && isConstantField(field)) {
                return@filter false
            }
            if (extension.keepPublicApi && (field.access and Opcodes.ACC_PUBLIC) != 0) return@filter false
            if (extension.excludeFields.any { it.matcher(field.name).matches() }) return@filter false
            if (hasKeepAnnotation(field.annotations)) return@filter false
            if (platformRules.shouldKeepField(field)) return@filter false
            if (extension.customKeepRules("field", emptyMap(), null)) return@filter false

            referencedFields.none { it.owner == field.owner && it.name == field.name && it.desc == field.desc }
        }

        val deadClasses = declaredClasses.filter { cls ->
            val className = cls.replace('/', '.')
            if (extension.excludePackages.any { matchesPackageRule(it, className) }) return@filter false
            if (extension.excludeClasses.any { matchesRule(it, className) }) return@filter false
            if (cls == "kotlin/Metadata") return@filter false
            if (allReferencedClasses.contains(cls)) return@filter false
            if (declaredMethods.any { it.owner == cls && it.name == "main" && (it.desc == "([Ljava/lang/String;)V" || it.desc == "()V") }) return@filter false
            if (extension.keepPublicApi && isPublicClass(cls, classScan)) return@filter false
            if (hasKeepAnnotation(classAnnotations[cls] ?: emptySet())) return@filter false
            if (platformRules.shouldKeepClass(cls, classAnnotations)) return@filter false
            if (extension.customKeepRules("class", classAnnotations, null)) return@filter false

            val usedMember = declaredMethods.any { it.owner == cls && allReferencedMethods.any { ref ->
                ref.owner == it.owner && ref.name == it.name && ref.desc == it.desc
            } } || declaredFields.any { it.owner == cls && referencedFields.any { ref ->
                ref.owner == it.owner && ref.name == it.name && ref.desc == it.desc
            } }
            !usedMember
        }

        val deadResources = if (extension.includeResources) {
            resScan.declared.filterNot { res ->
                allReferencedResources.contains(res) ||
                        platformRules.shouldKeepResource(res.first, res.second)
            }.toSet()
        } else emptySet()

        return DeadCodeModel(deadMethods, deadFields, deadClasses, deadResources)
    }

    private fun referencedMethodsWithOverrides(classScan: ClassScanModel): Set<MethodRef> {
        val references = classScan.referencedMethods.toMutableSet()
        for (reference in classScan.referencedMethods) {
            classScan.declaredMethods
                .filter { it.owner == reference.owner && it.name == reference.name && it.desc == reference.desc }
                .forEach(references::add)
            classScan.declaredMethods
                .filter { it.name == reference.name && it.desc == reference.desc }
                .filter { it.owner != reference.owner && isSubtypeOf(it.owner, reference.owner, classScan.classParents) }
                .forEach(references::add)
            classScan.declaredMethods
                .filter { it.name == reference.name && it.desc == reference.desc }
                .filter { it.owner != reference.owner && isSubtypeOf(reference.owner, it.owner, classScan.classParents) }
                .forEach(references::add)
        }
        return references
    }

    private fun isSubtypeOf(
        child: String,
        parent: String,
        classParents: Map<String, Set<String>>,
        visited: MutableSet<String> = mutableSetOf()
    ): Boolean {
        if (!visited.add(child)) {
            return false
        }
        val parents = classParents[child] ?: return false
        return parent in parents || parents.any { isSubtypeOf(it, parent, classParents, visited) }
    }

    private fun isExternalOverride(method: MethodRef, classScan: ClassScanModel): Boolean {
        return externalOverrideCache.getOrPut(method) {
            val parents = classScan.classParents[method.owner].orEmpty()
            parents.any { parent ->
                if (parent in classScan.declaredClasses) {
                    false
                } else {
                    hasExternalMethod(parent, method.name, method.desc, mutableSetOf())
                }
            }
        }
    }

    private fun hasExternalMethod(
        className: String,
        methodName: String,
        descriptor: String,
        visited: MutableSet<String>
    ): Boolean {
        if (!visited.add(className)) return false

        val type = try {
            Class.forName(className.replace('/', '.'), false, projectClassLoader)
        } catch (_: ClassNotFoundException) {
            return false
        }

        if (type.declaredMethods.any { method ->
                method.name == methodName &&
                        Type.getMethodDescriptor(method) == descriptor &&
                        !Modifier.isPrivate(method.modifiers) &&
                        !Modifier.isStatic(method.modifiers)
            }) {
            return true
        }

        type.superclass?.let { superclass ->
            if (hasExternalMethod(superclass.name.replace('.', '/'), methodName, descriptor, visited)) {
                return true
            }
        }
        return type.interfaces.any { interfaceType ->
            hasExternalMethod(interfaceType.name.replace('.', '/'), methodName, descriptor, visited)
        }
    }

    private fun createProjectClassLoader(): ClassLoader {
        val urls = project.configurations
            .filter { it.name == "runtimeClasspath" || it.name == "compileClasspath" }
            .flatMap { configuration ->
                try {
                    configuration.resolve().map { it.toURI().toURL() }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            .distinct()
            .toTypedArray()
        return URLClassLoader(urls, Thread.currentThread().contextClassLoader)
    }

    private fun matchesPackageRule(rule: Any, className: String): Boolean {
        return when (rule) {
            is Pattern -> rule.matcher(className).matches()
            is Regex -> rule.matches(className)
            is String -> className.startsWith(rule)
            else -> false
        }
    }

    private fun isConstantField(field: FieldRef): Boolean {
        return (field.access and (Opcodes.ACC_STATIC or Opcodes.ACC_FINAL)) ==
                (Opcodes.ACC_STATIC or Opcodes.ACC_FINAL) &&
                (field.compileTimeConstant || (field.name == "serialVersionUID" && field.desc == "J"))
    }

    private fun matchesRule(rule: Any, value: String): Boolean {
        return when (rule) {
            is Pattern -> rule.matcher(value).matches()
            is Regex -> rule.matches(value)
            is String -> value == rule
            else -> false
        }
    }

    private fun detectPlatformRules(): PlatformKeepRules {
        val platform = extension.platform
        if (platform != "auto") {
            return when (platform) {
                "android" -> AndroidKeepRules()
                "spring" -> SpringKeepRules()
                else -> KmmKeepRules()
            }
        }

        if (project.plugins.hasPlugin("com.android.application") ||
            project.plugins.hasPlugin("com.android.library")) {
            return AndroidKeepRules()
        }
        if (project.plugins.hasPlugin("org.springframework.boot")) {
            return SpringKeepRules()
        }

        val hasAndroidDep = project.configurations.any { config ->
            config.dependencies.any { dep ->
                dep.group == "androidx.appcompat" || dep.group == "com.google.android" ||
                        dep.group == "android" || dep.group == "com.android.support"
            }
        }
        if (hasAndroidDep) return AndroidKeepRules()

        val hasSpringDep = project.configurations.any { config ->
            config.dependencies.any { dep ->
                dep.group == "org.springframework" || dep.group == "org.springframework.boot"
            }
        }
        if (hasSpringDep) return SpringKeepRules()

        return KmmKeepRules()
    }

    private fun hasKeepAnnotation(annotations: Set<String>): Boolean {
        return extension.keepAnnotations.any { ann -> annotations.contains(ann) }
    }

    private fun isPublicClass(cls: String, classScan: ClassScanModel): Boolean {
        return false
    }
}
