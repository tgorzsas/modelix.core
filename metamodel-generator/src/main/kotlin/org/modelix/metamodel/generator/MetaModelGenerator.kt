package org.modelix.metamodel.generator

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.MemberName.Companion.member
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import org.modelix.metamodel.*
import org.modelix.model.api.*
import java.nio.file.Path
import kotlin.reflect.KClass
import org.modelix.model.data.LanguageData
import org.modelix.model.data.ConceptData
import org.modelix.model.data.PropertyData
import org.modelix.model.data.ChildLinkData
import org.modelix.model.data.ReferenceLinkData
import org.modelix.model.data.PropertyType

private val reservedPropertyNames: Set<String> = setOf(
    "constructor", // already exists on JS objects
) + IConcept::class.members.map { it.name }

class MetaModelGenerator(val outputDir: Path) {
    var alwaysUseNonNullableProperties: Boolean = true

    private val headerComment = "\ngenerated by modelix metamodel generator\n"

    private fun PropertyData.asKotlinType(): TypeName {
        return if (!optional || alwaysUseNonNullableProperties) type.asKotlinType() else type.asKotlinType().copy(nullable = true)
    }

    private fun FileSpec.write() {
        writeTo(outputDir)
    }

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

    fun generateRegistrationHelper(classFqName: String, languages: LanguageSet) {
        val typeName = ClassName(classFqName.substringBeforeLast("."), classFqName.substringAfterLast("."))
        val cls = TypeSpec.objectBuilder(typeName)
            .addProperty(PropertySpec.builder("languages", List::class.parameterizedBy(GeneratedLanguage::class))
                .initializer("listOf(" + languages.getLanguages().map { it.language.generatedClassName() }.joinToString(", ") { it.canonicalName } + ")")
                .build())
            .addFunction(FunSpec.builder("registerAll").addStatement("""languages.forEach { it.register() }""").build())
            .build()

        FileSpec.builder(typeName.packageName, typeName.simpleName)
            .addFileComment(headerComment)
            .addType(cls)
            .build()
            .write()
    }

    fun generate(languages: LanguageSet) {
        for (language in languages.getLanguages()) {
            language.language.packageDir().toFile().listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            val builder = FileSpec.builder(language.language.generatedClassName().packageName, language.language.generatedClassName().simpleName)
            val file = builder.addFileComment(headerComment)
                .addType(generateLanguage(language)).build()
            for (concept in language.getConceptsInLanguage()) {
                generateConceptFile(concept)
            }
            file.write()
        }
    }

    private fun generateLanguage(language: LanguageSet.LanguageInSet): TypeSpec {
        val builder = TypeSpec.objectBuilder(language.language.generatedClassName())
        val conceptNamesList = language.getConceptsInLanguage()
            .joinToString(", ") { it.simpleName }
        builder.addFunction(FunSpec.builder("getConcepts")
            .addModifiers(KModifier.OVERRIDE)
            .addCode(language.getConceptsInLanguage().map { it.conceptObjectType() }.toListLiteralCodeBlock())
            .build())
        builder.superclass(GeneratedLanguage::class)
        builder.addSuperclassConstructorParameter("\"${language.name}\"")
        for (concept in language.getConceptsInLanguage()) {
            builder.addProperty(PropertySpec.builder(concept.simpleName, concept.conceptObjectType())
                .initializer("%T", concept.conceptObjectType())
                .build())
        }
        return builder.build()
    }

    private fun generateConceptFile(concept: LanguageSet.ConceptInLanguage) {
        FileSpec.builder(concept.language.name, concept.concept.name)
            .addFileComment(headerComment)
            .addType(generateConceptObject(concept))
            .addType(generateConceptWrapperInterface(concept))
            .addType(generateConceptWrapperImpl(concept))
            .addType(generateNodeWrapperInterface(concept))
            .addType(generateNodeWrapperImpl(concept))
            .apply {
                // allow to write `nodes.myChildren` instead of `nodes.flatMap { it.myChildren }`
                for (feature in concept.directFeatures()) {
                    val receiverType = Iterable::class.asTypeName().parameterizedBy(concept.nodeWrapperInterfaceType())
                    when (val data = feature.data) {
                        is PropertyData -> {
                            addProperty(PropertySpec.builder(feature.validName, List::class.asTypeName().parameterizedBy(data.asKotlinType()))
                                .receiver(receiverType)
                                .getter(FunSpec.getterBuilder().addStatement("return map { it.%N }", feature.validName).build())
                                .build())
                            addProperty(PropertySpec.builder("raw_" + feature.validName, List::class.asTypeName().parameterizedBy(String::class.asTypeName().copy(nullable = true)))
                                .receiver(receiverType)
                                .getter(FunSpec.getterBuilder().addStatement("return map { it.%N }", "raw_" + feature.validName).build())
                                .build())
                        }
                        is ChildLinkData -> {
                            val targetType = data.type.parseConceptRef(concept.language).nodeWrapperInterfaceType()
                            addProperty(PropertySpec.builder(feature.validName, List::class.asTypeName().parameterizedBy(targetType))
                                .receiver(receiverType)
                                .getter(FunSpec.getterBuilder().addStatement("return flatMap { it.%N }", feature.validName).build())
                                .build())
                        }
                        is ReferenceLinkData -> {
                            val targetType = data.type.parseConceptRef(concept.language).nodeWrapperInterfaceType().copy(nullable = data.optional)
                            val rawTargetType = INode::class.asTypeName().copy(nullable = true)
                            addProperty(PropertySpec.builder(feature.validName, List::class.asTypeName().parameterizedBy(targetType))
                                .receiver(receiverType)
                                .getter(FunSpec.getterBuilder().addStatement("return map { it.%N }", feature.validName).build())
                                .build())
                            addProperty(PropertySpec.builder("raw_" + feature.validName, List::class.asTypeName().parameterizedBy(rawTargetType))
                                .receiver(receiverType)
                                .getter(FunSpec.getterBuilder().addStatement("return map { it.%N }", "raw_" + feature.validName).build())
                                .build())
                        }
                    }
                }
            }
            .build().write()
    }

    private fun generateConceptObject(concept: LanguageSet.ConceptInLanguage): TypeSpec {
        return TypeSpec.objectBuilder(concept.conceptObjectName()).apply {
            superclass(GeneratedConcept::class.asTypeName().parameterizedBy(
                concept.nodeWrapperImplType(),
                concept.conceptWrapperImplType()
            ))
            addSuperclassConstructorParameter("%S", concept.concept.name)
            addSuperclassConstructorParameter(concept.concept.abstract.toString())
            val instanceClassType = KClass::class.asClassName().parameterizedBy(concept.nodeWrapperImplType())
            addProperty(PropertySpec.builder(GeneratedConcept<*, *>::instanceClass.name, instanceClassType, KModifier.OVERRIDE)
                .initializer(concept.nodeWrapperImplName() + "::class")
                .build())
            addFunction(FunSpec.builder(GeneratedConcept<*, *>::typed.name)
                .addModifiers(KModifier.OVERRIDE)
                .addStatement("""return %T""", concept.conceptWrapperInterfaceType())
                .build())
            addProperty(PropertySpec.builder(IConcept::language.name, ILanguage::class, KModifier.OVERRIDE)
                .initializer(concept.language.generatedClassName().simpleName)
                .build())
            addFunction(FunSpec.builder(GeneratedConcept<*, *>::wrap.name)
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("node", INode::class)
                .addStatement("return %T(node)", concept.nodeWrapperImplType())
                .build())
            concept.concept.uid?.let { uid ->
                addFunction(FunSpec.builder(GeneratedConcept<*, *>::getUID.name)
                    .addModifiers(KModifier.OVERRIDE)
                    .addStatement(CodeBlock.of("return %S", uid).toString())
                    .build())
            }
            addFunction(FunSpec.builder(GeneratedConcept<*, *>::getDirectSuperConcepts.name)
                .addModifiers(KModifier.OVERRIDE)
                .addCode(concept.directSuperConcepts().map { it.conceptObjectType() }.toListLiteralCodeBlock())
                .returns(List::class.asTypeName().parameterizedBy(IConcept::class.asTypeName()))
                .build())
            for (feature in concept.directFeatures()) {
                when (val data = feature.data) {
                    is PropertyData -> {
                        val serializer = (if (!data.optional || alwaysUseNonNullableProperties) {
                            when (data.type) {
                                PropertyType.STRING -> MandatoryStringPropertySerializer::class
                                PropertyType.BOOLEAN -> MandatoryBooleanPropertySerializer::class
                                PropertyType.INT -> MandatoryIntPropertySerializer::class
                            }
                        } else {
                            when (data.type) {
                                PropertyType.STRING -> OptionalStringPropertySerializer::class
                                PropertyType.BOOLEAN -> OptionalBooleanPropertySerializer::class
                                PropertyType.INT -> OptionalIntPropertySerializer::class
                            }
                        }).asTypeName()
                        addProperty(PropertySpec.builder(feature.validName, GeneratedProperty::class.asClassName().parameterizedBy(data.asKotlinType()))
                            .initializer("""newProperty(%S, %T, ${data.optional})""", feature.originalName, serializer)
                            .build())
                    }
                    is ChildLinkData -> {
                        val methodName = if (data.multiple) "newChildListLink" else "newSingleChildLink"
                        addProperty(PropertySpec.builder(feature.validName, feature.generatedChildLinkType())
                            .initializer(
                                """$methodName(%S, ${data.optional}, %T, %T::class)""",
                                feature.originalName,
                                data.type.conceptObjectName().parseClassName(),
                                data.type.nodeWrapperInterfaceName().parseClassName()
                            )
                            .build())
                    }
                    is ReferenceLinkData -> {
                        addProperty(PropertySpec.builder(feature.validName, feature.generatedReferenceLinkType())
                            .initializer(
                                """newReferenceLink(%S, ${data.optional}, %T, %T::class)""",
                                feature.originalName,
                                data.type.conceptObjectName().parseClassName(),
                                data.type.nodeWrapperInterfaceName().parseClassName()
                            )
                            .build())
                    }
                }
            }
        }.build()
    }

    private fun generateConceptWrapperInterface(concept: LanguageSet.ConceptInLanguage): TypeSpec {
        return TypeSpec.interfaceBuilder(concept.conceptWrapperInterfaceType()).apply {
            addSuperinterface(ITypedConcept::class)
            for (extended in concept.extended()) {
                addSuperinterface(extended.conceptWrapperInterfaceType())
            }
            for (feature in concept.directFeatures()) {
                when (val data = feature.data) {
                    is PropertyData -> addProperty(PropertySpec.builder(feature.validName, GeneratedProperty::class.asClassName().parameterizedBy(data.asKotlinType())).build())
                    is ChildLinkData -> addProperty(PropertySpec.builder(feature.validName, feature.generatedChildLinkType()).build())
                    is ReferenceLinkData -> addProperty(PropertySpec.builder(feature.validName, feature.generatedReferenceLinkType()).build())
                }
            }

            addType(TypeSpec.companionObjectBuilder().apply {
                superclass(concept.conceptWrapperImplType())
                val t = if (concept.concept.abstract) IConceptOfTypedNode::class else INonAbstractConcept::class
                addSuperinterface(t.asTypeName().parameterizedBy(concept.nodeWrapperInterfaceType()))
                addFunction(FunSpec.builder(IConceptOfTypedNode<*>::getInstanceInterface.name)
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(KClass::class.asTypeName().parameterizedBy(concept.nodeWrapperInterfaceType()))
                    .addStatement("return %T::class", concept.nodeWrapperInterfaceType())
                    .build())
            }.build())
        }.build()
    }

    private fun generateConceptWrapperImpl(concept: LanguageSet.ConceptInLanguage): TypeSpec {
        return TypeSpec.classBuilder(concept.conceptWrapperImplType()).apply {
            addModifiers(KModifier.OPEN)
            if (concept.extends().isEmpty()) {
            } else {
                superclass(concept.extends().first().conceptWrapperImplType())
                for (extended in concept.extends().drop(1)) {
                    addSuperinterface(extended.conceptWrapperInterfaceType(), CodeBlock.of("%T", extended.conceptWrapperInterfaceType()))
                }
            }
            addSuperinterface(concept.conceptWrapperInterfaceType())

            primaryConstructor(FunSpec.constructorBuilder().addModifiers(KModifier.PROTECTED).build())

            addFunction(FunSpec.builder(ITypedConcept::untyped.name)
                .returns(IConcept::class)
                .addModifiers(KModifier.OVERRIDE)
                .addStatement("return %T", concept.conceptObjectType())
                .build())

            for (feature in concept.directFeaturesAndConflicts()) {
                when (val data = feature.data) {
                    is PropertyData -> {
                        addProperty(PropertySpec.builder(feature.validName, GeneratedProperty::class.asClassName().parameterizedBy(data.asKotlinType()))
                            .addModifiers(KModifier.OVERRIDE)
                            .initializer(feature.kotlinRef())
                            .build())
                    }
                    is ChildLinkData -> {
                        addProperty(PropertySpec.builder(feature.validName, feature.generatedChildLinkType())
                            .addModifiers(KModifier.OVERRIDE)
                            .initializer(feature.kotlinRef())
                            .build())
                    }
                    is ReferenceLinkData -> {
                        addProperty(PropertySpec.builder(feature.validName, feature.generatedReferenceLinkType())
                            .addModifiers(KModifier.OVERRIDE)
                            .initializer(feature.kotlinRef())
                            .build())
                    }
                }
            }
        }.build()
    }

    private fun generateNodeWrapperImpl(concept: LanguageSet.ConceptInLanguage): TypeSpec {
        return TypeSpec.classBuilder(concept.nodeWrapperImplType()).apply {
            addModifiers(KModifier.OPEN)
            addProperty(PropertySpec.builder(TypedNodeImpl::_concept.name, concept.conceptWrapperImplType(), KModifier.OVERRIDE)
                .getter(FunSpec.getterBuilder().addStatement("""return %T""", concept.conceptWrapperInterfaceType()).build())
                .build())

            if (concept.extends().size > 1) {
                // fix kotlin warning about ambiguity in case of multiple inheritance
                addFunction(FunSpec.builder(ITypedNode::unwrap.name)
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(INode::class)
                    .addStatement("return " + TypedNodeImpl::wrappedNode.name)
                    .build())
            }
            primaryConstructor(FunSpec.constructorBuilder().addParameter("_node", INode::class).build())
            if (concept.extends().isEmpty()) {
                superclass(TypedNodeImpl::class)
                addSuperclassConstructorParameter("_node")
            } else {
                superclass(concept.extends().first().nodeWrapperImplType())
                addSuperclassConstructorParameter("_node")
                for (extended in concept.extends().drop(1)) {
                    addSuperinterface(extended.nodeWrapperInterfaceType(), CodeBlock.of("%T(_node)", extended.nodeWrapperImplType()))
                }
            }
            addSuperinterface(concept.nodeWrapperInterfaceType())
            for (feature in concept.directFeaturesAndConflicts()) {
                when (val data = feature.data) {
                    is PropertyData -> {
                        addProperty(PropertySpec.builder(feature.validName, data.asKotlinType())
                            .addModifiers(KModifier.OVERRIDE)
                            .mutable(true)
                            .delegate(
                                """%T(unwrap(), %T.%N)""",
                                TypedPropertyAccessor::class.asTypeName(),
                                feature.concept.conceptObjectType(),
                                feature.validName,
                            )
                            .build())
                        addProperty(PropertySpec.builder("raw_" + feature.validName, String::class.asTypeName().copy(nullable = true))
                            .addModifiers(KModifier.OVERRIDE)
                            .mutable(true)
                            .delegate(
                                """%T(unwrap(), %T.%N.untyped())""",
                                RawPropertyAccessor::class.asTypeName(),
                                feature.concept.conceptObjectType(),
                                feature.validName,
                            )
                            .build())
                    }
                    is ChildLinkData -> {
                        // TODO resolve link.type and ensure it exists
                        val accessorSubclass = if (data.multiple) ChildListAccessor::class else SingleChildAccessor::class
                        val type = accessorSubclass.asClassName()
                            .parameterizedBy(
                                data.type.parseConceptRef(concept.language).nodeWrapperInterfaceType())
                        addProperty(PropertySpec.builder(feature.validName, type)
                            .addModifiers(KModifier.OVERRIDE)
                            .initializer(
                                """%T(%N(), %T.%N, %T, %T::class)""",
                                accessorSubclass.asTypeName(),
                                ITypedNode::unwrap.name,
                                feature.concept.conceptObjectType(),
                                feature.validName,
                                data.type.conceptObjectName().parseClassName(),
                                data.type.nodeWrapperInterfaceName().parseClassName()
                            )
                            .build())
                    }
                    is ReferenceLinkData -> {
                        val accessorClass = if (data.optional) OptionalReferenceAccessor::class else MandatoryReferenceAccessor::class
                        addProperty(PropertySpec.builder(feature.validName, data.type.parseConceptRef(concept.language).nodeWrapperInterfaceType().copy(nullable = data.optional))
                            .addModifiers(KModifier.OVERRIDE)
                            .mutable(true)
                            .delegate(
                                """%T(%N(), %T.%N, %T::class)""",
                                accessorClass.asTypeName(),
                                ITypedNode::unwrap.name,
                                feature.concept.conceptObjectType(),
                                feature.validName,
                                data.type.nodeWrapperInterfaceName().parseClassName()
                            )
                            .build())
                        addProperty(PropertySpec.builder("raw_" + feature.validName, INode::class.asTypeName().copy(nullable = true))
                            .addModifiers(KModifier.OVERRIDE)
                            .mutable(true)
                            .delegate(
                                """%T(${ITypedNode::unwrap.name}(), %T.%N)""",
                                RawReferenceAccessor::class.asClassName(),
                                feature.concept.conceptObjectType(),
                                feature.validName,
                            )
                            .build())
                    }
                }
            }
        }.build()
    }

    private fun generateNodeWrapperInterface(concept: LanguageSet.ConceptInLanguage): TypeSpec {
        return TypeSpec.interfaceBuilder(concept.nodeWrapperInterfaceType()).apply {
            if (concept.extends().isEmpty()) addSuperinterface(ITypedNode::class.asTypeName())
            for (extended in concept.extends()) {
                addSuperinterface(extended.nodeWrapperInterfaceType())
            }
            for (feature in concept.directFeatures()) {
                when (val data = feature.data) {
                    is PropertyData -> {
                        addProperty(PropertySpec.builder(feature.validName, data.asKotlinType())
                            .mutable(true)
                            .build())
                        addProperty(PropertySpec.builder("raw_" + feature.validName, String::class.asTypeName().copy(nullable = true))
                            .mutable(true)
                            .build())
                    }
                    is ChildLinkData -> {
                        // TODO resolve link.type and ensure it exists
                        val accessorSubclass = if (data.multiple) ChildListAccessor::class else SingleChildAccessor::class
                        val type = accessorSubclass.asClassName()
                            .parameterizedBy(
                                data.type.parseConceptRef(concept.language).nodeWrapperInterfaceType())
                        addProperty(PropertySpec.builder(feature.validName, type)
                            .build())
                    }
                    is ReferenceLinkData -> {
                        addProperty(PropertySpec.builder(feature.validName, data.type.parseConceptRef(concept.language).nodeWrapperInterfaceType().copy(nullable = data.optional))
                            .mutable(true)
                            .build())
                        addProperty(PropertySpec.builder("raw_" + feature.validName, INode::class.asTypeName().copy(nullable = true))
                            .mutable(true)
                            .build())
                    }
                }
            }
        }.build()
    }
}

fun PropertyType.asKotlinType(): TypeName {
    return when (this) {
        PropertyType.STRING -> String::class.asTypeName()
        PropertyType.BOOLEAN -> Boolean::class.asTypeName()
        PropertyType.INT -> Int::class.asTypeName()
    }
}
fun String.parseClassName() = ClassName(substringBeforeLast("."), substringAfterLast("."))
fun ConceptRef.conceptWrapperImplType() = ClassName(languageName, conceptName.conceptWrapperImplName())
fun ConceptRef.conceptWrapperInterfaceType() = ClassName(languageName, conceptName.conceptWrapperInterfaceName())
fun ConceptRef.nodeWrapperImplType() = ClassName(languageName, conceptName.nodeWrapperImplName())
fun ConceptRef.nodeWrapperInterfaceType() = ClassName(languageName, conceptName.nodeWrapperInterfaceName())

fun String.languageClassName() = "L_" + this.replace(".", "_")
fun LanguageData.generatedClassName()  = ClassName(name, name.languageClassName())
fun LanguageSet.LanguageInSet.simpleClassName()  = this.language.generatedClassName().simpleName
fun ConceptData.nodeWrapperInterfaceName() = name.nodeWrapperInterfaceName()
fun String.nodeWrapperInterfaceName() = fqNamePrefix("N_")
fun ConceptData.nodeWrapperImplName() = name.nodeWrapperImplName()
fun String.nodeWrapperImplName() = fqNamePrefix("_N_TypedImpl_")
fun ConceptData.conceptObjectName() = name.conceptObjectName()
fun String.conceptObjectName() = fqNamePrefix("_C_UntypedImpl_")
fun ConceptData.conceptWrapperImplName() = name.conceptWrapperImplName()
fun String.conceptWrapperImplName() = fqNamePrefix("_C_TypedImpl_")
fun ConceptData.conceptWrapperInterfaceName() = name.conceptWrapperInterfaceName()
fun String.conceptWrapperInterfaceName() = fqNamePrefix("C_")
private fun String.fqNamePrefix(prefix: String, suffix: String = ""): String {
    return if (this.contains(".")) {
        this.substringBeforeLast(".") + "." + prefix + this.substringAfterLast(".")
    } else {
        prefix + this
    } + suffix
}

fun LanguageSet.ConceptInLanguage.getConceptFqName() = language.name + "." + concept.name
fun LanguageSet.ConceptInLanguage.conceptObjectName() = concept.conceptObjectName()
fun LanguageSet.ConceptInLanguage.conceptObjectType() = ClassName(language.name, concept.conceptObjectName())
fun LanguageSet.ConceptInLanguage.nodeWrapperImplName() = concept.nodeWrapperImplName()
fun LanguageSet.ConceptInLanguage.nodeWrapperImplType() = ClassName(language.name, concept.nodeWrapperImplName())
fun LanguageSet.ConceptInLanguage.nodeWrapperInterfaceType() = ClassName(language.name, concept.nodeWrapperInterfaceName())
fun LanguageSet.ConceptInLanguage.conceptWrapperImplType() = ClassName(language.name, concept.conceptWrapperImplName())
fun LanguageSet.ConceptInLanguage.conceptWrapperInterfaceType() = ClassName(language.name, concept.conceptWrapperInterfaceName())

fun FeatureInConcept.kotlinRef() = CodeBlock.of("%T.%N", concept.conceptObjectType(), validName)
fun FeatureInConcept.generatedChildLinkType(): TypeName {
    val childConcept = (data as ChildLinkData).type.parseConceptRef(concept.language)
    val linkClass = if (data.multiple) GeneratedChildListLink::class else GeneratedSingleChildLink::class
    return linkClass.asClassName().parameterizedBy(
        childConcept.nodeWrapperInterfaceType(), childConcept.conceptWrapperInterfaceType())
}
fun FeatureInConcept.generatedReferenceLinkType(): TypeName {
    val targetConcept = (data as ReferenceLinkData).type.parseConceptRef(concept.language)
    return GeneratedReferenceLink::class.asClassName().parameterizedBy(
        targetConcept.nodeWrapperInterfaceType(), targetConcept.conceptWrapperInterfaceType())
}

private fun List<TypeName>.toListLiteralCodeBlock(): CodeBlock {
    val list = this
    return CodeBlock.builder().apply {
        add("return listOf(\n")
        withIndent {
            for (element in list) {
                add("%T,\n", element)
            }
        }
        add(")")
    }.build()
}