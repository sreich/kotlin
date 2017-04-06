/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.load.java.structure.impl.classFiles

import com.intellij.util.cls.ClsFormatException
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.kotlin.builtins.PrimitiveType
import org.jetbrains.kotlin.load.java.structure.JavaClassifierType
import org.jetbrains.kotlin.load.java.structure.JavaType
import org.jetbrains.kotlin.load.java.structure.JavaTypeParameter
import org.jetbrains.kotlin.name.Name
import org.jetbrains.org.objectweb.asm.Type
import java.text.CharacterIterator
import java.text.StringCharacterIterator

/**
 * Take a look at com.intellij.psi.impl.compiled.SignatureParsing
 */
class SignatureParsingComponent(resolver: ClassifierResolver) {
    companion object {
        private val JLO = "java.lang.Object"
    }

    private val JAVA_LANG_OBJECT_CLASSIFIER_TYPE: JavaClassifierType =
            PlainJavaClassifierType({ resolver.resolve(JLO) }, emptyList())

    fun parseTypeParametersDeclaration(signature: CharacterIterator, resolver: ClassifierResolver): List<JavaTypeParameter> {
        if (signature.current() != '<') {
            return emptyList()
        }

        val typeParameters = ContainerUtil.newArrayList<JavaTypeParameter>()
        signature.next()
        while (signature.current() != '>') {
            typeParameters.add(parseTypeParameter(signature, resolver))
        }
        signature.next()
        return typeParameters
    }

    private fun parseTypeParameter(signature: CharacterIterator, resolver: ClassifierResolver): JavaTypeParameter {
        val name = StringBuilder()
        while (signature.current() != ':' && signature.current() != CharacterIterator.DONE) {
            name.append(signature.current())
            signature.next()
        }
        if (signature.current() == CharacterIterator.DONE) {
            throw ClsFormatException()
        }
        val parameterName = name.toString()

        // postpone list allocation till a second bound is seen; ignore sole Object bound
        var bounds: MutableList<JavaClassifierType>? = null
        var jlo = false
        while (signature.current() == ':') {
            signature.next()
            val bound = parseTopLevelClassRefSignature(signature, resolver) ?: continue
            if (bounds == null) {
                if (JAVA_LANG_OBJECT_CLASSIFIER_TYPE === bound) {
                    jlo = true
                    continue
                }
                bounds = ContainerUtil.newSmartList()
                if (jlo) {
                    bounds.add(JAVA_LANG_OBJECT_CLASSIFIER_TYPE)
                }
            }
            bounds.add(bound)
        }

        return BinaryJavaTypeParameter(Name.identifier(parameterName), bounds ?: emptyList())
    }

    fun parseTopLevelClassRefSignature(signature: CharacterIterator, resolver: ClassifierResolver): JavaClassifierType? {
        return when (signature.current()) {
            'L' -> parseParameterizedClassRefSignature(signature, resolver)
            'T' -> parseTypeVariableRefSignature(signature, resolver)
            else -> null
        }
    }

    private fun parseTypeVariableRefSignature(signature: CharacterIterator, resolver: ClassifierResolver): JavaClassifierType? {
        val id = StringBuilder()

        signature.next()
        while (signature.current() != ';' && signature.current() != '>' && signature.current() != CharacterIterator.DONE) {
            id.append(signature.current())
            signature.next()
        }

        if (signature.current() == CharacterIterator.DONE) {
            throw ClsFormatException()
        }
        if (signature.current() == ';') {
            signature.next()
        }

        return PlainJavaClassifierType({ resolver.resolve(id.toString()) }, emptyList())
    }

    private fun parseParameterizedClassRefSignature(signature: CharacterIterator, resolver: ClassifierResolver): JavaClassifierType {
        val canonicalName = StringBuilder()

        val argumentGroups = ContainerUtil.newSmartList<List<JavaType>>()

        signature.next()
        while (signature.current() != ';' && signature.current() != CharacterIterator.DONE) {
            val c = signature.current()
            if (c == '<') {
                val group = mutableListOf<JavaType>()
                signature.next()
                do {
                    group.add(parseClassOrTypeVariableElement(signature, resolver))
                }
                while (signature.current() != '>')

                argumentGroups.add(group)
            }
            else if (c != ' ') {
                canonicalName.append(c)
            }
            signature.next()
        }

        if (signature.current() == CharacterIterator.DONE) {
            throw ClsFormatException()
        }
        signature.next()

        if (canonicalName.toString() == "java/lang/Object") return JAVA_LANG_OBJECT_CLASSIFIER_TYPE

        return PlainJavaClassifierType({ resolver.resolveByInternalName(canonicalName.toString()) }, argumentGroups.reversed().flatten())
    }

    private fun parseClassOrTypeVariableElement(signature: CharacterIterator, resolver: ClassifierResolver): JavaType {
        val variance = parseVariance(signature)
        if (variance == JavaSignatureVariance.STAR) {
            return PlainJavaWildcardType(bound = null, isExtends = true)
        }

        val type = parseTypeString(signature, resolver)
        if (variance == JavaSignatureVariance.NO_VARIANCE) return type

        return PlainJavaWildcardType(type, isExtends = variance == JavaSignatureVariance.PLUS)
    }


    private enum class JavaSignatureVariance {
        PLUS, MINUS, STAR, NO_VARIANCE
    }

    private fun parseVariance(signature: CharacterIterator): JavaSignatureVariance {
        var advance = true

        val variance = when (signature.current()) {
            '+' -> JavaSignatureVariance.PLUS
            '-' -> JavaSignatureVariance.MINUS
            '*' -> JavaSignatureVariance.STAR
            '.', '=' -> JavaSignatureVariance.NO_VARIANCE
            else -> {
                advance = false
                JavaSignatureVariance.NO_VARIANCE
            }
        }

        if (advance) {
            signature.next()
        }

        return variance
    }

    private fun parseDimensions(signature: CharacterIterator): Int {
        var dimensions = 0
        while (signature.current() == '[') {
            dimensions++
            signature.next()
        }
        return dimensions
    }

    fun parseTypeString(signature: CharacterIterator, resolver: ClassifierResolver): JavaType {
        val dimensions = parseDimensions(signature)

        val type: JavaType = parseTypeWithoutVarianceAndArray(signature, resolver) ?: throw ClsFormatException()
        return (1..dimensions).fold(type) { result, _ -> PlainJavaArrayType(result) }
    }

    fun mapAsmType(type: Type, resolver: ClassifierResolver) = parseTypeString(StringCharacterIterator(type.descriptor), resolver)

    private fun parseTypeWithoutVarianceAndArray(signature: CharacterIterator, resolver: ClassifierResolver) =
            when (signature.current()) {
                'L' -> parseParameterizedClassRefSignature(signature, resolver)
                'T' -> parseTypeVariableRefSignature(signature, resolver)

                'B' -> parsePrimitiveType(signature, PrimitiveType.BYTE)
                'C' -> parsePrimitiveType(signature, PrimitiveType.CHAR)
                'D' -> parsePrimitiveType(signature, PrimitiveType.DOUBLE)
                'F' -> parsePrimitiveType(signature, PrimitiveType.FLOAT)
                'I' -> parsePrimitiveType(signature, PrimitiveType.INT)
                'J' -> parsePrimitiveType(signature, PrimitiveType.LONG)
                'Z' -> parsePrimitiveType(signature, PrimitiveType.BOOLEAN)
                'S' -> parsePrimitiveType(signature, PrimitiveType.SHORT)
                'V' -> parsePrimitiveType(signature, null)
                else -> null
            }

    private fun parsePrimitiveType(signature: CharacterIterator, primitiveType: PrimitiveType?): JavaType {
        signature.next()
        return PlainJavaPrimitiveType(primitiveType)
    }
}

