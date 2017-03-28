/*
 * Copyright 2010-2015 JetBrains s.r.o.
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

package org.jetbrains.kotlin.load.java.lazy

import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.load.java.lazy.descriptors.LazyJavaTypeParameterDescriptor
import org.jetbrains.kotlin.load.java.structure.JavaTypeParameter
import org.jetbrains.kotlin.load.java.structure.JavaTypeParameterListOwner

interface TypeParameterResolver {
    object EMPTY : TypeParameterResolver {
        override fun resolveTypeParameter(javaTypeParameter: JavaTypeParameter): TypeParameterDescriptor? = null
        override fun resolveTypeParameter(p1: String) = null
    }

    fun resolveTypeParameter(javaTypeParameter: JavaTypeParameter): TypeParameterDescriptor?

    fun resolveTypeParameter(p1: String): JavaTypeParameter?
}

class LazyJavaTypeParameterResolver(
        private val c: LazyJavaResolverContext,
        private val containingDeclaration: DeclarationDescriptor,
        typeParameterOwner: JavaTypeParameterListOwner,
        private val typeParametersIndexOffset: Int
) : TypeParameterResolver {
    private val typeParameters: Map<String, Pair<Int, JavaTypeParameter>> = mutableMapOf<String, Pair<Int, JavaTypeParameter>>().apply {
        for ((index, typeParameter) in typeParameterOwner.typeParameters.withIndex()) {
            put(typeParameter.name.asString(), index to typeParameter)
        }
    }
    private val resolve = c.storageManager.createMemoizedFunctionWithNullableValues {
        name: String ->
        typeParameters[name]?.let { (index, typeParameter) ->
            LazyJavaTypeParameterDescriptor(c.child(this), typeParameter, typeParametersIndexOffset + index, containingDeclaration)
        }
    }

    override fun resolveTypeParameter(javaTypeParameter: JavaTypeParameter): TypeParameterDescriptor? {
        return resolve(javaTypeParameter.name.asString()) ?: c.typeParameterResolver.resolveTypeParameter(javaTypeParameter)
    }

    override fun resolveTypeParameter(p1: String) =
            resolve(p1)?.javaTypeParameter ?: c.typeParameterResolver.resolveTypeParameter(p1)
}
