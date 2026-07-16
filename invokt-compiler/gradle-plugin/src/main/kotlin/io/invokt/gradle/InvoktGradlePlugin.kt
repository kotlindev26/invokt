package io.invokt.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/**
 * Attaches the invokt compiler plugin (static @Import binding) to every JVM
 * and Kotlin/Native compilation. Metadata compilations are skipped — they
 * produce no code, the imported() marker body simply stays there.
 */
class InvoktGradlePlugin : KotlinCompilerPluginSupportPlugin {

    override fun apply(target: Project) = Unit

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
        kotlinCompilation.platformType == KotlinPlatformType.jvm ||
            kotlinCompilation.platformType == KotlinPlatformType.native

    override fun getCompilerPluginId(): String = "io.invokt.compiler"

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(groupId = "io.invokt", artifactId = "invokt-compiler-plugin", version = "0.1.0")

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> =
        kotlinCompilation.target.project.provider { emptyList() }
}
