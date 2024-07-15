/*
 * Copyright 2017-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.koin.compiler.scanner

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.validate
import org.koin.compiler.metadata.DEFINITION_ANNOTATION_LIST_TYPES
import org.koin.compiler.metadata.KoinMetaData
import org.koin.core.annotation.Definition
import org.koin.core.annotation.Module
import org.koin.core.annotation.Property
import org.koin.core.annotation.PropertyValue

class KoinMetaDataScanner(
    private val logger: KSPLogger
) {

    private val moduleMetadataScanner = ModuleScanner(logger)
    private val componentMetadataScanner = ClassComponentScanner(logger)
    private val functionMetadataScanner = FunctionComponentScanner(logger)

    private var validModuleSymbols = listOf<KSAnnotated>()
    private var validDefinitionSymbols = listOf<KSAnnotated>()
    private var defaultProperties = listOf<KSAnnotated>()
    private var externalDefinitions = listOf<KSDeclaration>()

    private val definitionAnnotationName = Definition::class.simpleName

    @OptIn(KspExperimental::class)
    fun scanSymbols(resolver: Resolver): List<KSAnnotated> {
        val moduleSymbols = resolver.getSymbolsWithAnnotation(Module::class.qualifiedName!!).toList()
        val definitionSymbols = DEFINITION_ANNOTATION_LIST_TYPES.flatMap { annotation -> resolver.getSymbolsWithAnnotation(annotation.qualifiedName!!) }

        validModuleSymbols = moduleSymbols.filter { it.validate() }
        validDefinitionSymbols = definitionSymbols.filter { it.validate() }

        val invalidModuleSymbols = moduleSymbols.filter { !it.validate() }
        val invalidDefinitionSymbols = definitionSymbols.filter { !it.validate() }
        val invalidSymbols = invalidModuleSymbols + invalidDefinitionSymbols
        if (invalidSymbols.isNotEmpty()) {
            logger.logging("Invalid definition symbols found.")
            logInvalidEntities(invalidSymbols)
            return invalidSymbols
        }

        defaultProperties = resolver.getSymbolsWithAnnotation(PropertyValue::class.qualifiedName!!).toList().filter { it.validate() }

        logger.logging("All symbols are valid")

        externalDefinitions = resolver.getDeclarationsFromPackage("org.koin.ksp.generated")
            .filter { it.annotations.any { it.shortName.asString() == definitionAnnotationName } }
            .toList()

        if (externalDefinitions.isNotEmpty()) {
            logger.logging("external definitions: ${externalDefinitions.size}")
        } else {
            logger.logging("no external definition")
        }

        return emptyList()
    }

    fun scanKoinModules(defaultModule: KoinMetaData.Module): List<KoinMetaData.Module> {
        val moduleList = scanClassModules()
        val index = moduleList.generateScanComponentIndex()
        scanClassComponents(defaultModule, index)
        scanFunctionComponents(defaultModule, index)
        scanDefaultProperties(index+defaultModule)
        scanExternalDefinitions(index)
        return moduleList
    }

    private fun scanDefaultProperties(index: List<KoinMetaData.Module>) {
        val propertyValues: List<KoinMetaData.PropertyValue> = defaultProperties.mapNotNull { def ->
            def.annotations
                .first { it.shortName.asString() == PropertyValue::class.simpleName }
                .let { a ->
                    val id = a.arguments.first().value?.toString()
                    val field = (a.parent as? KSDeclaration)
                    id?.let {
                        field?.qualifiedName?.asString()?.let {
                            KoinMetaData.PropertyValue(id = id ,  it)
                        }
                    }
                }
        }

        val allProperties = index
            .flatMap { it.definitions }
            .flatMap { it.parameters }
            .filterIsInstance<KoinMetaData.DefinitionParameter.Property>()

        //attribute default values
        propertyValues.forEach { pv -> allProperties
            .filter { it.value == pv.id }
                .forEach { it.defaultField = pv.field }
        }
    }

    private fun scanExternalDefinitions(index: List<KoinMetaData.Module>) {
        externalDefinitions
            .mapNotNull { def ->
                def.annotations
                    .first { it.shortName.asString() == definitionAnnotationName }.arguments.first().value?.toString()
                    ?.let {
                        KoinMetaData.ExternalDefinition(targetPackage = it, name = def.simpleName.asString())
                    }
            }
            .forEach { extDef ->
                // add to first module that accept
                val module = index.firstOrNull { it.acceptDefinition(extDef.targetPackage) }
                module?.externalDefinitions?.add(extDef)
            }
    }

    private fun scanClassModules(): List<KoinMetaData.Module> {
        logger.logging("scan modules ...")
        return validModuleSymbols
            .filterIsInstance<KSClassDeclaration>()
            .map { moduleMetadataScanner.createClassModule(it) }
            .toList()
    }

    private fun List<KoinMetaData.Module>.generateScanComponentIndex(): List<KoinMetaData.Module> {
        val moduleList = hashMapOf<String, KoinMetaData.Module>()
        val emptyScanList = arrayListOf<KoinMetaData.Module>()
        forEach { module ->
            module.componentScan?.let { scan ->
                when (scan.packageName) {
                    "" -> emptyScanList.add(module)
                    else -> if (moduleList.contains(scan.packageName)) {
                        val existing = moduleList[scan.packageName]!!
                        error("@ComponentScan with '${scan.packageName}' from module ${module.name} is already declared in ${existing.name}. Please fix @ComponentScan value ")
                    } else {
                        moduleList[scan.packageName] = module
                    }
                }
            }
        }
        return moduleList.values + emptyScanList
    }

    private fun scanFunctionComponents(
        defaultModule: KoinMetaData.Module,
        scanComponentIndex: List<KoinMetaData.Module>
    ): List<KoinMetaData.Definition> {
        logger.logging("scan functions ...")

        val definitions = validDefinitionSymbols
            .filterIsInstance<KSFunctionDeclaration>()
            .mapNotNull { functionMetadataScanner.createFunctionDefinition(it) }
            .toList()

        definitions.forEach { addToModule(it, defaultModule, scanComponentIndex) }
        return definitions
    }

    private fun scanClassComponents(
        defaultModule: KoinMetaData.Module,
        scanComponentIndex: List<KoinMetaData.Module>
    ): List<KoinMetaData.Definition> {
        logger.logging("scan definitions ...")

        val definitions = validDefinitionSymbols
            .filterIsInstance<KSClassDeclaration>()
            .map { componentMetadataScanner.createClassDefinition(it) }
            .toList()
        definitions.forEach { addToModule(it, defaultModule, scanComponentIndex) }
        return definitions
    }

    private fun addToModule(
        definition: KoinMetaData.Definition,
        defaultModule: KoinMetaData.Module,
        modules: List<KoinMetaData.Module>
    ) {
        val definitionPackage = definition.packageName
        val foundModule = modules.firstOrNull { it.acceptDefinition(definitionPackage) } ?: defaultModule
        val alreadyExists = foundModule.definitions.contains(definition)
        if (!alreadyExists) {
            if (foundModule == defaultModule) {
                logger.warn("No module found for '$definitionPackage.${definition.label}'. Definition is added to 'defaultModule'")
            }
            foundModule.definitions.add(definition)
        } else {
            logger.logging("skip addToModule - definition(class) -> $definition -> module $foundModule - already exists")
        }
    }

    private fun logInvalidEntities(classDeclarationList: List<KSAnnotated>) {
        classDeclarationList.forEach { logger.logging("Invalid entity: $it") }
    }
}
