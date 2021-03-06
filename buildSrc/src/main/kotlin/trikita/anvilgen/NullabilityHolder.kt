package trikita.anvilgen

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.*
import java.io.IOException
import java.lang.reflect.Method

class NullabilityHolder(isSourceSdk: Boolean, private val classLoader: ClassLoader) {

    private val nullabilityMap: MutableMap<MethodSignature, Boolean?> = mutableMapOf()
    private val nullableLiteral: String
    private val nonNullableLiteral: String

    init {
        if (isSourceSdk) {
            nullableLiteral = "Landroidx/annotation/RecentlyNullable;"
            nonNullableLiteral = "Landroidx/annotation/RecentlyNonNull;"
        } else {
            nullableLiteral = "Landroidx/annotation/Nullable;"
            nonNullableLiteral = "Landroidx/annotation/NonNull;"
        }
    }

    fun fillClassNullabilityInfo(rawClassName: String) {
        val cn = ClassNode()
        val cr = try {
            ClassReader(classLoader.getResourceAsStream(rawClassName))
        } catch (io: IOException) {
            return
        }

        cr.accept(cn, 0)

        val className = rawClassName.replace(".class", "").replace("/", ".")

        cn.methods.forEach { methodNode : MethodNode ->
            if (methodNode.name != "<init>"
                && methodNode.invisibleParameterAnnotations?.isNotEmpty() == true
                && methodNode.localVariables?.size == 2
            ) {

                // TODO check if we really can get null for invisibleParameterAnnotations[0]
                val hasNullable =
                    hasNullableOrNonNullAnnotation(methodNode.invisibleParameterAnnotations[0])
                val argType = convertTypeNameFromRaw((methodNode.localVariables[1] as LocalVariableNode).desc)
                val formattedMethodName = formatMethodName(methodNode.name, 1)
                formattedMethodName?.let {
                    val signature = MethodSignature(className, it.formattedName, argType)
                    nullabilityMap[signature] = hasNullable
                }
            }
        }
    }

    private fun convertTypeNameFromRaw(typeName: String): String =
        typeName
            .replace("/", ".")
            .replace("$", ".")
            .let {
                if (it.startsWith("L")) {
                    it.replaceFirst("L", "")
                } else it
            }.let {
                if (it.endsWith(";")) {
                    it.replaceFirst(";", "")
                } else it
            }

    fun hasNullableOrNonNullAnnotation(annotationList: List<AnnotationNode>?): Boolean? =
        annotationList
            ?.map {
                when(it.desc) {
                    nullableLiteral -> true
                    nonNullableLiteral -> false
                    else -> null
                }
            }
            ?.firstOrNull()

    fun isParameterNullable(m: Method): Boolean? {
        val formattedMethodName = formatMethodName(m.name, 1)
        return formattedMethodName?.let {
            val signature = MethodSignature(
                m.declaringClass.canonicalName,
                it.formattedName,
                m.parameters[0].type.canonicalName
            )

            nullabilityMap[signature]
        }
    }

    fun isParameterNullable(className: String, methodName: String, argType: String): Boolean? {
        return nullabilityMap[MethodSignature(className, methodName, argType)]
    }

}

data class MethodSignature(val className: String, val methodName: String, val firstArgType: String)
