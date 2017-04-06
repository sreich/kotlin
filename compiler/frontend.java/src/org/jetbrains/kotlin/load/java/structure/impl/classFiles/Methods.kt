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
import org.jetbrains.kotlin.load.java.structure.*
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type
import java.text.CharacterIterator
import java.text.StringCharacterIterator

abstract class BinaryJavaMethodBase(
        override val access: Int,
        override val containingClass: JavaClass,
        val valueParameters: List<JavaValueParameter>,
        val typeParameters: List<JavaTypeParameter>,
        override val name: Name
) : JavaMember, BinaryJavaAnnotationOwner, BinaryJavaModifierListOwner {
    override val annotationsByFqName by buildLazyValueForMap()

    override val annotations: Collection<JavaAnnotation> = mutableListOf()

    companion object {
        private class MethodInfo(
                val returnType: JavaType,
                val typeParameters: List<JavaTypeParameter>,
                val valueParameterTypes: List<JavaType>
        )

        fun create(
                name: String,
                access: Int,
                desc: String,
                signature: String?,
                containingClass: JavaClass,
                resolver: ClassifierResolver,
                signatureParsingComponent: SignatureParsingComponent
        ): Pair<JavaMember, MethodVisitor> {
            val isConstructor = "<init>" == name
            val isVarargs = access.isSet(Opcodes.ACC_VARARGS)

            val generic = signature != null
            val info: MethodInfo =
                    if (signature != null)
                        parseMethodSignature(signature, signatureParsingComponent, resolver)
                    else
                        parseMethodDescription(desc, resolver, signatureParsingComponent)

            val isInnerClassConstructor = isConstructor && containingClass.outerClass != null && !containingClass.isStatic

            info.typeParameters.associateByTo(resolver.typeParameters) { it.name.identifier }

            val parameterTypes = info.valueParameterTypes
            val parameterList = ContainerUtil.newSmartList<JavaValueParameter>()
            val paramCount = parameterTypes.size
            for (i in 0..paramCount - 1) {
                // omit synthetic inner class constructor parameter
                if (i == 0 && !generic && isInnerClassConstructor) continue

                val type = parameterTypes[i]
                val isEllipsisParam = isVarargs && i == paramCount - 1

                parameterList.add(BinaryJavaValueParameter(null, type, isEllipsisParam))
            }

            val paramIgnoreCount = if (isInnerClassConstructor) 1 else 0

            val member: JavaMember =
                    if (isConstructor)
                        BinaryJavaConstructor(access, containingClass, parameterList, info.typeParameters)
                    else
                        BinaryJavaMethod(
                                access, containingClass, parameterList, info.typeParameters, Name.identifier(name), info.returnType
                        )

            return member to AnnotationsCollectorMethodVisitor(member, resolver, paramIgnoreCount)
        }

        private fun parseMethodDescription(
                desc: String,
                resolver: ClassifierResolver,
                signatureParsingComponent: SignatureParsingComponent
        ): MethodInfo {
            val returnType = signatureParsingComponent.mapAsmType(Type.getReturnType(desc), resolver)
            val parameterTypes = Type.getArgumentTypes(desc).map { signatureParsingComponent.mapAsmType(it, resolver) }

            return MethodInfo(returnType, emptyList(), parameterTypes)
        }

        private fun parseMethodSignature(
                signature: String,
                signatureParsingComponent: SignatureParsingComponent,
                resolver: ClassifierResolver
        ): MethodInfo {
            val iterator = StringCharacterIterator(signature)
            val typeParameters = signatureParsingComponent.parseTypeParametersDeclaration(iterator, resolver)

            if (iterator.current() != '(') throw ClsFormatException()
            iterator.next()
            val paramTypes: List<JavaType>
            if (iterator.current() == ')') {
                paramTypes = emptyList()
            }
            else {
                paramTypes = ContainerUtil.newSmartList()
                while (iterator.current() != ')' && iterator.current() != CharacterIterator.DONE) {
                    paramTypes.add(signatureParsingComponent.parseTypeString(iterator, resolver))
                }
                if (iterator.current() != ')') throw ClsFormatException()
            }
            iterator.next()

            val returnType = signatureParsingComponent.parseTypeString(iterator, resolver)

            return MethodInfo(returnType, typeParameters, paramTypes)
        }
    }
}

class BinaryJavaMethod(
        flags: Int,
        containingClass: JavaClass,
        valueParameters: List<JavaValueParameter>,
        typeParameters: List<JavaTypeParameter>,
        name: Name,
        override val returnType: JavaType
) : BinaryJavaMethodBase(
        flags, containingClass, valueParameters, typeParameters, name
), JavaMethod {
    // TODO
    override var hasAnnotationParameterDefaultValue: Boolean = false
}

class BinaryJavaConstructor(
        flags: Int,
        containingClass: JavaClass,
        valueParameters: List<JavaValueParameter>,
        typeParameters: List<JavaTypeParameter>
) : BinaryJavaMethodBase(
        flags, containingClass, valueParameters, typeParameters,
        SpecialNames.NO_NAME_PROVIDED
), JavaConstructor
