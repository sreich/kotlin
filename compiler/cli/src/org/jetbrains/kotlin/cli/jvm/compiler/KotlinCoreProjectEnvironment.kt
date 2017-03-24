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

package org.jetbrains.kotlin.cli.jvm.compiler

import com.intellij.core.CoreJavaFileManager
import com.intellij.core.JavaCoreApplicationEnvironment
import com.intellij.core.JavaCoreProjectEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import java.lang.reflect.Field
import java.lang.reflect.Modifier

open class KotlinCoreProjectEnvironment(
        disposable: Disposable,
        applicationEnvironment: JavaCoreApplicationEnvironment
) : JavaCoreProjectEnvironment(disposable, applicationEnvironment) {
    override fun createCoreFileManager() = KotlinCliJavaFileManagerImpl(PsiManager.getInstance(project))

    override fun addSourcesToClasspath(root: VirtualFile) {
        val field = JavaCoreProjectEnvironment::class.java.getDeclaredField("myFileManager")
        field.isAccessible = true
        val old = field.get(this) as KotlinCliJavaFileManagerImpl
        val modifiersField = Field::class.java.getDeclaredField("modifiers")
        modifiersField.isAccessible = true
        modifiersField.setInt(field, field.modifiers and Modifier.FINAL.inv())
        field.set(this, CoreJavaFileManager(myPsiManager))
        super.addSourcesToClasspath(root)
        field.set(this, old)
    }
}
