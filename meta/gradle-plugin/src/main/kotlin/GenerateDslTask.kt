package dev.inkremental.meta.gradle

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import dev.inkremental.meta.model.*
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.*
import java.io.File

abstract class GenerateDslTask : DefaultTask() {
    @get:Input abstract var modelFile: File
    @get:Classpath @get:Optional abstract var configuration: Configuration?
    @get:OutputDirectory abstract var outputDir: File

    @TaskAction
    fun renderModel() {
        val json = ModelJson()

        val superResolver = SuperResolver()

        configuration?.resolvedConfiguration?.resolvedArtifacts
            ?.map { it.file }
            ?.map { json.parse(ModuleModel.serializer(), it.readText()).backlink() }
            ?.flatMap { it.views }
            ?.forEach { superResolver.processView(it) }

        val inputStr = modelFile.readText()
        val model = json.parse(ModuleModel.serializer(), inputStr).backlink()
        model.views.forEach { superResolver.processView(it) }

        superResolver.finalize()

        if(model.views.isEmpty()) return // don't generate empty setter for manual modules

        val setterType = ClassName(model.modulePackage, "${model.name}Setter")

        generateFile(setterType) {
            addObject(setterType) {
                addKdoc(
                    """DSL for creating views and settings their attributes.
                    |This file has been generated by
                    |{@code gradle $name}
                    |${model.javadocContains}.
                    |Please, don't edit it manually unless for debugging.""".trimMargin()
                )
                addModifiers(KModifier.PUBLIC)
                addSuperinterface(INKREMENTAL.nestedClass("AttributeSetter").parameterizedBy(ANY))

                addFunction("set") {
                    addParameter("v", VIEW)
                    addParameter("name", STRING)
                    addParameter("arg", ANY_N)
                    addParameter("old", ANY_N)
                    returns(BOOLEAN)
                    addModifiers(KModifier.PUBLIC, KModifier.OVERRIDE)

                    addCode {
                        beginControlFlow("return when (name)")
                        model.views
                            .flatMap { it.attrs }
                            .groupBy { it.name }
                            .mapValues { (_, it) -> it.sortedBy { it.type.name } }
                            .forEach { (name, attrs) ->
                                val filtered = attrs.filter { a ->
                                    attrs.none { b ->
                                        // TODO what?
                                        a != b && a.type == b.type && a.owner.isAssignableFrom(b.owner)
                                    }
                                }

                                beginControlFlow("%S -> when", name)
                                filtered.forEach {
                                    add(
                                        if (it.isListener)
                                            it.buildListener()
                                        else
                                            it.buildSetter()
                                    )
                                }
                                add("else -> false\n")
                                endControlFlow()
                            }
                        add("else -> false\n")
                        endControlFlow()
                    }
                }
            }
        }

        val attr = MemberName(PACKAGE, "attr")
        val bind = MemberName(PACKAGE, "bind")
        val v = MemberName(PACKAGE, "v")

        model.views.forEach { view ->
            val viewName = view.name
            val scopeType = view.scopeType
            val viewType = view.starProjectedType

            generateFile(scopeType.packageName, viewName) {
                addFunction(viewName.lowerFirstChar()) {
                    addParameter(
                        "configure",
                        LambdaTypeName.get(receiver = scopeType, returnType = UNIT)
                    ) {
                        defaultValue("{}")
                    }
                    addCode(CodeBlock.of("return %M<%T>(configure.%M(%T))", v, viewType, bind, scopeType))
                }
                addClass(scopeType) {
                    addModifiers(KModifier.PUBLIC, KModifier.ABSTRACT)
                    when (val superType = view.superType) {
                        null -> superclass(ClassName(PACKAGE, ROOT_VIEW_SCOPE))
                        is ViewModelSupertype.Resolved -> superclass(superType.type.scopeType)
                        is ViewModelSupertype.Unresolved -> error("View supertype should be resolved at this point")
                    }
                    addCompanionObject {
                        superclass(scopeType)
                        addInitializerBlock {
                            add("%T.registerAttributeSetter(%T)\n", INKREMENTAL, setterType)
                            if (model.manualSetter != null) {
                                add("${INKREMENTAL.simpleName}.registerAttributeSetter(%M)\n", model.manualSetter)
                            }
                        }
                    }
                    view.attrs
                        .map { attrModel ->
                            addFunction(attrModel.name) {
                                if (!handleTransformersForDsl(this, attrModel, attr)) {
                                    addParameter("arg", attrModel.type.argType.copy(nullable = attrModel.isNullable))
                                    returns(UNIT)
                                    addCode(CodeBlock.of("return %M(%S, arg)", attr, attrModel.name))
                                }
                            }
                        }
                }
            }
        }
    }

    private fun handleTransformersForDsl(builder: FunSpec.Builder, attrModel: AttrModel, attr: MemberName): Boolean {
        val transformers = attrModel.transformers ?: return false
        if (transformers.isEmpty()) return false

        var needsToBreak = false
        transformers.forEach { dslTransformer ->
            when (dslTransformer) {
                DslTransformer.FLoatPixelToDipSizeTransformer -> {
                    if (attrModel.type.argType.toString() == "kotlin.Float"){
                        builder.addParameter("arg", ClassName.bestGuess("dev.inkremental.dsl.android.Dip"))
                        builder.returns(UNIT)
                        builder.addCode(CodeBlock.of("return %M(%S, arg.value)", attr, attrModel.name))
                        needsToBreak = true
                    }
                }
                DslTransformer.RequiresApi21Transformer -> {
                    builder.addAnnotation(AnnotationSpec.builder(androidx.annotation.RequiresApi::class)
                            .addMember("api = android.os.Build.VERSION_CODES.LOLLIPOP")
                            .build())
                }
            }
        }
        return needsToBreak
    }

    private fun FileSpec.Builder.addDefaultAnnotations() =
        addAnnotation(
            AnnotationSpec.builder(Suppress::class)
                .addMember("\"DEPRECATION\", \"UNCHECKED_CAST\", \"MemberVisibilityCanBePrivate\", \"unused\"")
                .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                .build()
        )

    private fun generateFile(packageName: String, fileName: String, builderAction: FileSpec.Builder.() -> Unit) =
        buildFile(packageName, fileName) {
            addDefaultAnnotations()
            builderAction()
        }.writeTo(outputDir.also { it.mkdirs() })

    private fun generateFile(mainClassName: ClassName, builderAction: FileSpec.Builder.() -> Unit) =
        buildFile(mainClassName) {
            addDefaultAnnotations()
            builderAction()
        }.writeTo(outputDir.also { it.mkdirs() })

    private fun AttrModel.buildListener(): CodeBlock {
        val body = buildCodeBlock {
            if(type.isSamLike) {
                add("arg as %T\n", type.argType.copy(nullable = isNullable))
            }
            if (type.isSamLike && type.isInterface) {
                val function = type.functions.first()
                val args = function.argsString

                beginControlFlow("v.$setterName { $args ->")
                add(function.buildListenerCode(putReturn = false, functionalType = true))
                endControlFlow()
            } else {
                val listener = TypeSpec.anonymousClassBuilder().apply {
                    if (type.isInterface) {
                        addSuperinterface(type.plainType)
                    } else {
                        superclass(type.plainType)
                    }
                    type.functions
                        .map { it.buildListenerFunction(functionalType = type.isSamLike) }
                        .forEach { addFunction(it) }
                }.build()
                addStatement("v.$setterName(%L)", listener)
            }
        }

        return buildCodeBlock {
            val checkedType = if (type.isSamLike) {
                FUNCTION_STAR
            } else {
                type.plainType
            }

            if (owner.isRoot) {
                // @formatter:off
                beginControlFlow("arg == null ->")
                    addStatement("v.$setterName(null as? %T?)", type.plainType)
                    addStatement("true")
                endControlFlow()
                beginControlFlow("arg is %T ->", checkedType)
                    add(body)
                    addStatement("true")
                endControlFlow()
                // @formatter:on
            } else {
                val v = owner.parametrizedType?.let { "(v as $it)" } ?: "v"
                // @formatter:off
                beginControlFlow("v is %T -> when", owner.starProjectedType)
                    beginControlFlow("arg == null ->")
                        addStatement("$v.$setterName(null as? %T)", type.plainType.copy(nullable = true))
                        addStatement("true")
                    endControlFlow()
                    beginControlFlow("arg is %T ->", checkedType)
                        add(body)
                        addStatement("true")
                    endControlFlow()
                    addStatement("else -> false")
                endControlFlow()
                // @formatter:on
            }
        }
    }

    private fun FunctionModel.buildListenerFunction(functionalType: Boolean): FunSpec = buildFunSpec(name) {
        addModifiers(KModifier.PUBLIC, KModifier.OVERRIDE)
        returns(returnType)
        addParameters(parameterSpecs())
        addCode(buildListenerCode(putReturn = true, functionalType = functionalType))
    }

    private fun FunctionModel.buildListenerCode(putReturn: Boolean, functionalType: Boolean): CodeBlock = buildCodeBlock {
        if(putReturn) {
            add("return ")
        }
        // TODO this can blow up if listener method has method named "arg"
        add("arg")
        if(!functionalType) {
            add(".%L", name)
        }
        add("($argsString).also·{ %T.render() }\n", INKREMENTAL)
    }

    private fun AttrModel.buildSetter(): CodeBlock {
        val argAsParam = when {
            isVarArg -> "*arg"
            isNullable -> "arg"
            isArray -> "arg as? %T"
            else -> "arg"
        }

        // TODO check if getter is present and if so, use property assignment, else use setter call
        return buildCodeBlock {
            if (owner.isRoot) {
                beginControlFlow("arg is %T ->", type.starProjectedType.copy(nullable = isNullable))
                addStatement("v.$setterName($argAsParam)", type.parametrizedType)
                addStatement("true")
                endControlFlow()
            } else {
                val v = owner.parametrizedType?.let { "(v as $it)" } ?: "v"

                if (!handleTransformersForAttrSetter(transformers, this, owner, v, setterName, argAsParam, type)) {
                    beginControlFlow(
                            "v is %T && arg is %T ->",
                            owner.starProjectedType,
                            type.starProjectedType.copy(nullable = isNullable)
                    )
                    addStatement("$v.$setterName($argAsParam)", type.parametrizedType)
                }
                addStatement("true")
                endControlFlow()
            }
        }
    }

    private fun handleTransformersForAttrSetter(transformers: List<DslTransformer>?,
                                                builder: CodeBlock.Builder,
                                                owner: ViewModel,
                                                v: String,
                                                setterName: String,
                                                argAsParam: String,
                                                type: TypeModel): Boolean {
        val transformers = transformers ?: return false
        if (transformers.isEmpty()) return false

        var needsToBreak = false
        transformers.forEach { transformer ->
            when (transformer) {
                DslTransformer.FLoatPixelToDipSizeTransformer -> {
                    builder.beginControlFlow(
                            "v is %T && arg is Int ->",
                            owner.starProjectedType
                    )
                    builder.addStatement("$v.$setterName(%M($argAsParam).toFloat())", MemberName(PACKAGE, "dip"))
                    needsToBreak = true
                }
            }
        }
        return needsToBreak
    }
}

fun String.lowerFirstChar() = get(0).toLowerCase() + substring(1)
