package org.modelix.metamodel.generator

import java.nio.file.Path
import kotlin.io.path.writeText
import org.modelix.model.data.LanguageData
import org.modelix.model.data.PropertyType

class TypescriptMMGenerator(val outputDir: Path) {

    private fun LanguageData.packageDir(): Path {
        val packageName = name
        var packageDir = outputDir
        if (packageName.isNotEmpty()) {
            for (packageComponent in packageName.split('.').dropLastWhile { it.isEmpty() }) {
                packageDir = packageDir.resolve(packageComponent)
            }
        }
        return packageDir
    }

    fun generate(languages: IProcessedLanguageSet) {
        generate(languages as ProcessedLanguageSet)
    }

    internal fun generate(languages: ProcessedLanguageSet) {
        for (language in languages.getLanguages()) {
            // TODO delete old files from previous generation
            outputDir
                .resolve(language.generatedClassName().simpleName + ".ts")
                .writeText(generateLanguage(language))

            generateRegistry(languages)
        }
    }

    private fun generateRegistry(languages: ProcessedLanguageSet) {
        outputDir.resolve("index.ts").writeText("""
            import { LanguageRegistry } from "@modelix/ts-model-api";
            ${languages.getLanguages().joinToString("\n") { """
                import { ${it.simpleClassName()} } from "./${it.simpleClassName()}";
            """.trimIndent() }}
            export function registerLanguages() {
                ${languages.getLanguages().joinToString("\n") { """
                    LanguageRegistry.INSTANCE.register(${it.simpleClassName()}.INSTANCE);
                """.trimIndent() }}
            }
        """.trimIndent())
    }

    private fun generateLanguage(language: ProcessedLanguage): String {
        val conceptNamesList = language.getConcepts()
            .joinToString(", ") { it.conceptWrapperInterfaceName() }

        return """
            import {
                ChildListAccessor,
                GeneratedConcept, 
                GeneratedLanguage,
                IConceptJS,
                INodeJS,
                ITypedNode, 
                SingleChildAccessor,
                TypedNode
            } from "@modelix/ts-model-api";
            
            ${language.languageDependencies().joinToString("\n") {
                """import * as ${it.simpleClassName()} from "./${it.simpleClassName()}";"""
            }}
            
            export class ${language.simpleClassName()} extends GeneratedLanguage {
                public static INSTANCE: ${language.simpleClassName()} = new ${language.simpleClassName()}();
                constructor() {
                    super("${language.name}")
                    
                    ${language.getConcepts().joinToString("\n") { concept -> """
                        this.nodeWrappers.set("${concept.uid}", (node: INodeJS) => new ${concept.nodeWrapperImplName()}(node))
                    """.trimIndent() }}
                }
                public getConcepts() {
                    return [$conceptNamesList]
                }
            }
            
            ${language.getConcepts().joinToString("\n") { generateConcept(it) }.replaceIndent("            ")}
        """.trimIndent()
    }

    private fun generateConcept(concept: ProcessedConcept): String {
        val featuresImpl = concept.getAllSuperConceptsAndSelf().flatMap { it.getOwnRoles() }.joinToString("\n") { feature ->
            when (feature) {
                is ProcessedProperty -> {
                    val rawValueName = feature.rawValueName()
                    when (feature.type) {
                        PropertyType.INT -> {
                            """
                                public set ${feature.generatedName}(value: number) {
                                    this.${rawValueName} = value.toString();
                                }
                                public get ${feature.generatedName}(): number {
                                    let str = this.${rawValueName};
                                    return str ? parseInt(str) : 0;
                                }
                                
                            """.trimIndent()
                        }
                        PropertyType.BOOLEAN -> {
                            """
                                public set ${feature.generatedName}(value: boolean) {
                                    this.${rawValueName} = value ? "true" : "false";
                                }
                                public get ${feature.generatedName}(): boolean {
                                    return this.${rawValueName} === "true";
                                }
                                
                            """.trimIndent()
                        }
                        else -> ""
                    } + """
                        public set $rawValueName(value: string | undefined) {
                            this.node.setPropertyValue("${feature.originalName}", value)
                        }
                        public get $rawValueName(): string | undefined {
                            return this.node.getPropertyValue("${feature.originalName}")
                        }
                    """.trimIndent()
                }
                is ProcessedReferenceLink -> """
                    
                """.trimIndent()
                is ProcessedChildLink -> {
                    val accessorClassName = if (feature.multiple) "ChildListAccessor" else "SingleChildAccessor"
                    val typeRef = feature.type.resolved
                    val languagePrefix = typeRef.languagePrefix(concept.language)
                    """
                        public ${feature.generatedName}: $accessorClassName<$languagePrefix${typeRef.nodeWrapperInterfaceName()}> = new $accessorClassName(this.node, "${feature.originalName}")
                    """.trimIndent()
                }
                else -> ""
            }
        }
        val features = concept.getOwnRoles().joinToString("\n") { feature ->
            when (feature) {
                is ProcessedProperty -> {
                    when (feature.type) {
                        PropertyType.BOOLEAN -> {
                            """
                                ${feature.generatedName}: boolean
                                
                            """.trimIndent()
                        }
                        PropertyType.INT -> {
                            """
                                ${feature.generatedName}: number
                                
                            """.trimIndent()
                        }
                        else -> ""
                    } +
                    """
                        ${feature.rawValueName()}: string | undefined
                    """.trimIndent()
                }
                is ProcessedReferenceLink -> """
                    
                """.trimIndent()
                is ProcessedChildLink -> {
                    val accessorClassName = if (feature.multiple) "ChildListAccessor" else "SingleChildAccessor"
                    """
                        ${feature.generatedName}: $accessorClassName<${feature.type.resolved.tsInterfaceRef(concept.language)}>
                    """.trimIndent()
                }
                else -> ""
            }
        }
        val interfaceList = concept.getDirectSuperConcepts().joinToString(", ") { it.tsInterfaceRef(concept.language) }.ifEmpty { "ITypedNode" }
        // TODO extend first super concept do reduce the number of generated members
        return """
            
            export class ${concept.conceptWrapperImplName()} extends GeneratedConcept {
              constructor(uid: string) {
                super(uid);
              }
              getDirectSuperConcepts(): Array<IConceptJS> {
                return [${concept.getDirectSuperConcepts().joinToString(",") { it.languagePrefix(concept.language) + it.conceptWrapperInterfaceName() }}];
              }
            }
            export const ${concept.conceptWrapperInterfaceName()} = new ${concept.conceptWrapperImplName()}("${concept.uid}")
            
            export interface ${concept.nodeWrapperInterfaceName()} extends $interfaceList {
                ${features}
            }
            
            export function isOfConcept_${concept.name}(node: ITypedNode): node is ${concept.nodeWrapperInterfaceName()} {
                return '${concept.markerPropertyName()}' in node.constructor;
            }
            
            export class ${concept.nodeWrapperImplName()} extends TypedNode implements ${concept.nodeWrapperInterfaceName()} {
                ${concept.getAllSuperConceptsAndSelf().joinToString("\n") {
                    """public static readonly ${it.markerPropertyName()}: boolean = true"""
                }}
                ${featuresImpl.replaceIndent("                ")}
            }
            
        """.trimIndent()
    }
}

private fun ProcessedConcept.markerPropertyName() = "_is_" + toString().replace(".", "_")
internal fun ProcessedConcept.tsClassName() = this.language.name.languageClassName() + "." + this.name
internal fun ProcessedConcept.tsInterfaceRef(contextLanguage: ProcessedLanguage) = languagePrefix(contextLanguage) + nodeWrapperInterfaceName()
internal fun ProcessedConcept.languagePrefix(contextLanguage: ProcessedLanguage): String {
    return if (this.language == contextLanguage) {
        ""
    } else {
        this.language.name.languageClassName() + "."
    }
}
internal fun ProcessedLanguage.languageDependencies(): List<ProcessedLanguage> {
    val languageNames = this.getConcepts()
        .flatMap { it.getAllSuperConceptsAndSelf().flatMap { it.getOwnRoles() } }
        .mapNotNull {
            when (it) {
                is ProcessedLink -> it.type.resolved
                else -> null
            }
        }
        .plus(this.getConcepts().flatMap { it.getDirectSuperConcepts() })
        .map { it.language.name }
        .toSet()
    return languageSet.getLanguages().filter { languageNames.contains(it.name) }.minus(this)
}

private fun ProcessedProperty.rawValueName() = when (type) {
    PropertyType.STRING -> generatedName
    else -> "raw_" + generatedName
}