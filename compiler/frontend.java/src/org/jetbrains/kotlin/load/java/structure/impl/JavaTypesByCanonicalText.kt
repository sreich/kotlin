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

package org.jetbrains.kotlin.load.java.structure.impl

import org.jetbrains.kotlin.load.java.structure.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.kotlin.utils.parseClassifierType

class JavaClassifierTypeByFqName(val qName: String, val arguments: List<JavaType>) : JavaClassifierType {

    companion object {
        @JvmStatic
        fun createByCanonicalText(canonicalText: String): JavaClassifierType {
            return parseClassifierType.time {
                val (qName, args, isArray, left) = parseClassifierOrArrayTypeInfo(canonicalText, 0)
                assert(!isArray) { "Classifier type can't be an array" }
                assert(left == canonicalText.length) { "Symbols left in canonical name: ${canonicalText.substring(left)}" }

                JavaClassifierTypeByFqName(qName, args)
            }
        }
    }

    override fun getClassifier(f: JavaClassifierFactory): JavaClassifier? = f.findClassifier(qName)
    override fun getTypeArguments(f: JavaClassifierFactory): List<JavaType> = arguments
    override fun isRaw(f: JavaClassifierFactory): Boolean = arguments.isEmpty() &&
                                                            f.findClassifier(qName)?.safeAs<JavaClass>()?.typeParameters?.isNotEmpty() == true

    override val annotations: Collection<JavaAnnotation>
        get() = emptyList()
    override val presentableText: String
        get() = canonicalText

    override fun findAnnotation(fqName: FqName) = null

    override val isDeprecatedInJavaDoc: Boolean
        get() = false

    override val canonicalText: String
        get() = qName
}

private class JavaArrayTypeByCanonicalName(override val componentType: JavaType) : JavaArrayType
private class JavaWildcardTypeByCanonicalName(override val bound: JavaType?, override val isExtends: Boolean) : JavaWildcardType
private data class Parsed(val classFqName: String, val arguments: List<JavaType>, var isArray: Boolean, val rest: Int)

private fun parseClassifierOrArrayTypeInfo(s: String, start: Int): Parsed {
    val argumentGroups = mutableListOf<List<JavaType>>()
    var i = start
    val fqNameBuilder = StringBuilder()
    var isArray = false

    loop@ while (i < s.length) {
        when (s[i]) {
            '<' -> {
                val (j, args) = parseArgs(s, i)
                i = j
                argumentGroups.add(args)
            }
            '[' -> {
                isArray = true
                i++
                assert(s.getOrNull(i) == ']') { "Expected ] for array type" }
                i++
                break@loop
            }
            ' ', ',', '&', '>' -> break@loop
            else -> fqNameBuilder.append(s[i++])
        }
    }

    return Parsed(fqNameBuilder.toString(), argumentGroups.reversed().flatten(), isArray, i)
}

private fun parseClassifierOrArrayType(s: String, start: Int): Pair<Int, JavaType> {
    val (fqName, args, isArray, rest) = parseClassifierOrArrayTypeInfo(s, start)

    val classifierType = JavaClassifierTypeByFqName(fqName, args)
    val result = if (isArray) JavaArrayTypeByCanonicalName(classifierType) else classifierType

    return rest to result
}

private fun parseArgs(s: String, start: Int): Pair<Int, List<JavaType>> {
    val result = mutableListOf<JavaType>()
    var i = start
    assert(s[i] == '<')
    i++

    loop@ while (true) {
        when (s[i]) {
            '?' -> {
                val (j, isExtends, type) = parseWildcard(s, i)
                result.add(JavaWildcardTypeByCanonicalName(type, isExtends))
                i = j
            }
            '>' -> {
                i++
                return i to result
            }
            ' ', ',' -> {
                i++
                continue@loop
            }
            else -> {
                val (j, type) = parseClassifierOrArrayType(s, i)
                i = j
                result.add(type)
            }
        }
    }
}

private fun parseWildcard(s: String, start: Int): Triple<Int, Boolean, JavaType?> {
    var i = start
    assert(s[i] == '?')
    i++

    if (s[i] != ' ') {
        return Triple(i, true, null)
    }
    i++

    val left = s.substring(i)
    var isExtends = true
    when {
        left.startsWith("extends ") -> {
            i += 8
        }

        left.startsWith("super ") -> {
            isExtends = false
            i += 6
        }
        else -> throw IllegalStateException("Unexpected wildcard type: $left")
    }

    var (rest, javaType) = parseClassifierOrArrayType(s, i)

    while (s.getOrNull(rest) == ' ' && s.getOrNull(rest + 1) == '&') {
        rest = parseClassifierOrArrayType(s, rest + 2).first
    }

    return Triple(rest, isExtends, javaType)
}
