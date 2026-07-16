package io.invokt.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.messageCollector

@OptIn(ExperimentalCompilerApi::class)
class InvoktCompilerPluginRegistrar : CompilerPluginRegistrar() {

    override val pluginId: String = "io.invokt.compiler"

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        IrGenerationExtension.registerExtension(
            InvoktIrGenerationExtension(configuration.messageCollector),
        )
    }
}
