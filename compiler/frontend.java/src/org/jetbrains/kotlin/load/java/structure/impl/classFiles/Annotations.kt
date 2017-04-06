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

import org.jetbrains.kotlin.load.java.structure.JavaAnnotation
import org.jetbrains.kotlin.load.java.structure.JavaAnnotationArgument
import org.jetbrains.kotlin.load.java.structure.JavaClass
import org.jetbrains.kotlin.load.java.structure.JavaMember
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type

internal class AnnotationsCollectorMethodVisitor(
        private val member: JavaMember,
        private val resolver: ClassifierResolver,
        private val parametersToSkipNumber: Int
) : MethodVisitor(Opcodes.ASM5) {
    override fun visitAnnotationDefault(): AnnotationVisitor? {
        member.safeAs<BinaryJavaMethod>()?.hasAnnotationParameterDefaultValue = true
        return null
    }

    override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor? {
        member.annotations.cast<MutableCollection<JavaAnnotation>>().add(BinaryJavaAnnotation(desc, resolver))
        return null
    }

    override fun visitParameterAnnotation(parameter: Int, desc: String, visible: Boolean): AnnotationVisitor? {
        val index = parameter - parametersToSkipNumber
        if (index < 0) return null
        member.safeAs<BinaryJavaMethodBase>()?.valueParameters?.get(index)?.annotations?.cast<MutableCollection<JavaAnnotation>>()
                ?.add(BinaryJavaAnnotation(desc, resolver))

        return null
    }
}

class BinaryJavaAnnotation(
        desc: String,
        resolver: ClassifierResolver
) : JavaAnnotation, AnnotationVisitor(Opcodes.ASM5) {
    private val javaClass by lazy(LazyThreadSafetyMode.NONE) {
        resolver.convertInternalNameToJavaClassifier(Type.getType(desc).internalName)
    }

    override val arguments: Collection<JavaAnnotationArgument>
        get() = emptyList()

    override val classId: ClassId?
        get() = javaClass.first.safeAs<JavaClass>()?.classId() ?: ClassId.topLevel(FqName(javaClass.second))

    override fun resolve() = javaClass.first as? JavaClass
}

private fun JavaClass.classId(): ClassId? {
    val fqName = fqName ?: return null
    if (outerClass == null) return ClassId.topLevel(fqName)

    val outerClassId = outerClass!!.classId() ?: return null

    return ClassId(outerClassId.packageFqName, outerClassId.relativeClassName.child(name), false)
}
