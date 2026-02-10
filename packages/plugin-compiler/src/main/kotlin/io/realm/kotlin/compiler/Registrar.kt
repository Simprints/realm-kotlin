package io.realm.kotlin.compiler

import com.google.auto.service.AutoService
import io.realm.kotlin.compiler.fir.model.RealmModelRegistrar
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.resolve.extensions.SyntheticResolveExtension
@AutoService(CompilerPluginRegistrar::class)
@OptIn(ExperimentalCompilerApi::class)
class Registrar : CompilerPluginRegistrar() {

    override val pluginId: String = "io.realm.kotlin"

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        // IMPORTANT: Initialize the messageCollector here so the Logger can access it
        // This resolves the UninitializedPropertyAccessException
        messageCollector = configuration.get(
            CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY,
            MessageCollector.NONE
        )

        SchemaCollector.properties.clear()

        // K1: Synthetic Resolve Extensions
        SyntheticResolveExtension.registerExtension(RealmModelSyntheticCompanionExtension())
        SyntheticResolveExtension.registerExtension(RealmModelSyntheticMethodsExtension())

        // K2: FIR Extension
        FirExtensionRegistrarAdapter.registerExtension(RealmModelRegistrar())

        // IR: Lowering Extension (This is what was crashing)
        IrGenerationExtension.registerExtension(RealmModelLoweringExtension())
    }

    override val supportsK2: Boolean = true
}