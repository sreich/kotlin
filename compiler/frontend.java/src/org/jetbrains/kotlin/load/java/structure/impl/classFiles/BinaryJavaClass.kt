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

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.containers.ContainerUtil
import gnu.trove.THashMap
import org.jetbrains.kotlin.load.java.structure.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.addIfNotNull
import org.jetbrains.org.objectweb.asm.*
import java.text.CharacterIterator
import java.text.StringCharacterIterator

class BinaryJavaClass(
        val virtualFile: VirtualFile,
        override val outerClass: JavaClass?,
        private val resolver: ClassifierResolver,
        private val signatureParsingComponent: SignatureParsingComponent,
        override var access: Int = 0
) : ClassVisitor(Opcodes.ASM5), JavaClass, BinaryJavaModifierListOwner, BinaryJavaAnnotationOwner {
    override lateinit var fqName: FqName
    lateinit var myInternalName: String

    override val annotations: MutableCollection<JavaAnnotation> = mutableListOf()
    override lateinit var typeParameters: List<JavaTypeParameter>
    override lateinit var supertypes: Collection<JavaClassifierType>
    override val methods = mutableListOf<JavaMethod>()
    override val fields = mutableListOf<JavaField>()
    override val constructors = mutableListOf<JavaConstructor>()

    override val annotationsByFqName by buildLazyValueForMap()

    private val innerClassNameToAccess: MutableMap<Name, Int> = THashMap()
    override val innerClassNames get() = innerClassNameToAccess.keys

    init {
        ClassReader(virtualFile.contentsToByteArray()).accept(
                this,
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES
        )
    }

    override fun visitMethod(access: Int, name: String, desc: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
        if (access.isSet(Opcodes.ACC_SYNTHETIC) || access.isSet(Opcodes.ACC_BRIDGE) || name == "<clinit>") return null

        // skip semi-synthetic enum methods
        val isEnum = isEnum
        if (isEnum) {
            // TODO
            if (name == "<init>") return null
            if (name == "values" && desc.startsWith("()")) return null
            if (name == "valueOf" && desc.startsWith("(Ljava/lang/String;)")) return null
        }

        val (member, visitor) = BinaryJavaMethodBase.create(name, access, desc, signature, this, resolver.copy(), signatureParsingComponent)

        when (member) {
            is JavaMethod -> methods.add(member)
            is JavaConstructor -> constructors.add(member)
            else -> error("Unexpected member: ${member.javaClass}")
        }

        return visitor
    }

    override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
        if (access.isSet(Opcodes.ACC_SYNTHETIC)) return
        if (innerName == null || outerName == null) return

        if (myInternalName == outerName) {
            innerClassNameToAccess[resolver.mapInternalNameToFqName(name).shortName()] = access
        }
    }

    override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?
    ) {
        this.access = this.access or access
        myInternalName = name
        fqName = resolver.mapInternalNameToFqName(name)

        if (signature != null) {
            parseClassSignature(signature)
        }
        else {
            this.typeParameters = emptyList()
            this.supertypes = mutableListOf<JavaClassifierType>().apply {
                addIfNotNull(superName?.convertInternalNameToClassifierType())
                interfaces?.forEach {
                    addIfNotNull(it.convertInternalNameToClassifierType())
                }
            }
        }
    }

    private fun parseClassSignature(signature: String) {
        val iterator = StringCharacterIterator(signature)
        this.typeParameters = signatureParsingComponent.parseTypeParametersDeclaration(iterator, resolver).also {
            for (typeParameter in it) {
                resolver.typeParameters.put(typeParameter.name.identifier, typeParameter)
            }
        }

        val supertypes = ContainerUtil.newSmartList<JavaClassifierType>()
        supertypes.addIfNotNull(signatureParsingComponent.parseTopLevelClassRefSignature(iterator, resolver))
        while (iterator.current() != CharacterIterator.DONE) {
            supertypes.addIfNotNull(signatureParsingComponent.parseTopLevelClassRefSignature(iterator, resolver))
        }
        this.supertypes = supertypes
    }

    private fun String.convertInternalNameToClassifierType(): JavaClassifierType =
            PlainJavaClassifierType({ resolver.convertInternalNameToJavaClassifier(this) }, emptyList())

    override fun visitField(access: Int, name: String, desc: String, signature: String?, value: Any?): FieldVisitor? {
        if (access.isSet(Opcodes.ACC_SYNTHETIC)) return null

        val type = signatureParsingComponent.parseTypeString(StringCharacterIterator(signature ?: desc), resolver)

        return BinaryJavaField(Name.identifier(name), access, this, access.isSet(Opcodes.ACC_ENUM), type, value).run {
            fields.add(this)

            object : FieldVisitor(Opcodes.ASM5) {
                override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor? {
                    this@run.annotations.add(BinaryJavaAnnotation(desc, resolver))
                    return null
                }
            }
        }
    }

    override fun visitAnnotation(desc: String?, visible: Boolean): AnnotationVisitor? {
        // TODO:
        return null
    }

    override val name: Name
        get() = fqName.shortName()

    override fun findInnerClass(name: Name): JavaClass? {
        val access = innerClassNameToAccess[name] ?: return null

        return virtualFile.parent.findChild("${virtualFile.nameWithoutExtension}$$name.class")?.let {
            BinaryJavaClass(it, this, resolver.copy(), signatureParsingComponent, access)
        }
    }

    override val isInterface get() = isSet(Opcodes.ACC_INTERFACE)
    override val isAnnotationType get() = isSet(Opcodes.ACC_ANNOTATION)
    override val isEnum get() = isSet(Opcodes.ACC_ENUM)
    override val lightClassOriginKind: LightClassOriginKind? get() = null
}
