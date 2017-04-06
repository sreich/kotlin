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

import gnu.trove.THashMap
import org.jetbrains.kotlin.load.java.structure.JavaClass
import org.jetbrains.kotlin.load.java.structure.JavaTypeParameter
import org.jetbrains.kotlin.name.FqName

class ClassifierResolver(
        val typeParameters: MutableMap<String, JavaTypeParameter> = THashMap(),
        private val classes: (String) -> JavaClass?
) {
    fun resolve(qName: String) = (typeParameters[qName] ?: classes(qName)) to qName

    fun copy() = ClassifierResolver(THashMap(this.typeParameters), classes)

    fun mapInternalNameToFqName(name: String) = FqName(convertInternalNameToFqName(name))

    // Copy-pasted from com.intellij.psi.impl.compiled.StubBuildingVisitor.GUESSING_MAPPER
    // It may give an incorrect result for classes containing `$` in their names
    private fun convertInternalNameToFqName(internalName: String): String {
        var canonicalText = internalName

        if (canonicalText.indexOf('$') >= 0) {
            val sb = StringBuilder(canonicalText)
            var updated = false
            for (p in 0..sb.length - 1) {
                val c = sb[p]
                if (c == '$' && p > 0 && sb[p - 1] != '/' && p < sb.length - 1 && sb[p + 1] != '$') {
                    sb.setCharAt(p, '.')
                    updated = true
                }
            }
            if (updated) {
                canonicalText = sb.toString()
            }
        }

        return canonicalText.replace('/', '.')
    }

    fun convertInternalNameToJavaClassifier(internalName: String) = resolve(mapInternalNameToFqName(internalName).asString())

    fun resolveByInternalName(c: String) = resolve(convertInternalNameToFqName(c))
}
