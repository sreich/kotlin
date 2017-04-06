/*
 * Copyright 2010-2015 KtBrains s.r.o.
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

package org.jetbrains.kotlin.idea.debugger

import com.intellij.debugger.SourcePosition
import com.intellij.debugger.engine.DebugProcess
import com.intellij.debugger.engine.DebuggerUtils
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.sun.jdi.AbsentInformationException
import com.sun.jdi.ReferenceType
import org.jetbrains.kotlin.codegen.binding.CodegenBinding.*
import org.jetbrains.kotlin.codegen.coroutines.containsNonTailSuspensionCalls
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.SimpleFunctionDescriptor
import org.jetbrains.kotlin.fileClasses.NoResolveFileClassesProvider
import org.jetbrains.kotlin.fileClasses.getFileClassInternalName
import org.jetbrains.kotlin.idea.debugger.breakpoints.getLambdasAtLineIfAny
import org.jetbrains.kotlin.idea.debugger.evaluate.KotlinDebuggerCaches
import org.jetbrains.kotlin.idea.debugger.evaluate.KotlinDebuggerCaches.ComputedClassNames
import org.jetbrains.kotlin.idea.debugger.evaluate.KotlinDebuggerCaches.ComputedClassNames.Companion.EMPTY
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.isObjectLiteral
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.inline.InlineUtil
import java.util.*

class DebuggerClassNameProvider(
        delegate: DebugProcess,
        scopes: List<GlobalSearchScope>,
        val findInlineUseSites: Boolean = true,
        val alwaysReturnLambdaParentClass: Boolean = true
) : DebugProcess by delegate {
    companion object {
        internal val CLASS_ELEMENT_TYPES = arrayOf<Class<out PsiElement>>(
                KtFile::class.java,
                KtClassOrObject::class.java,
                KtProperty::class.java,
                KtNamedFunction::class.java,
                KtFunctionLiteral::class.java,
                KtAnonymousInitializer::class.java)

        internal fun getRelevantElement(element: PsiElement): PsiElement? {
            for (elementType in CLASS_ELEMENT_TYPES) {
                if (elementType.isInstance(element)) {
                    return element
                }
            }

            // Do not copy the array (*elementTypes) if the element is one we look for
            return runReadAction { PsiTreeUtil.getNonStrictParentOfType(element, *CLASS_ELEMENT_TYPES) }
        }
    }

    val inlineUsagesSearcher = InlineCallableUsagesSearcher(this, scopes)

    fun getClassesForPosition(position: SourcePosition): List<ReferenceType> {
        return doGetClassesForPosition(position) { className, lineNumber ->
            virtualMachineProxy.classesByName(className).flatMap { findTargetClasses(it, lineNumber) }
        }
    }

    fun getOuterClassInternalNamesForPosition(position: SourcePosition): List<String> {
        return doGetClassesForPosition(position) { className, _ -> listOf(className) }
    }

    @PublishedApi
    @Suppress("NON_TAIL_RECURSIVE_CALL")
    internal tailrec fun getOuterClassNamesForElement(element: PsiElement?): ComputedClassNames {
        if (element == null) return EMPTY
        val actualElement = getRelevantElement(element)

        return when (actualElement) {
            is KtFile -> {
                val fileClassName = runReadAction { NoResolveFileClassesProvider.getFileClassInternalName(actualElement) }.toJdiName()
                ComputedClassNames.Cached(fileClassName)
            }
            is KtClassOrObject -> {
                val enclosingElementForLocal = runReadAction { KtPsiUtil.getEnclosingElementForLocalDeclaration(actualElement) }
                if (enclosingElementForLocal != null) { // A local class
                    getOuterClassNamesForElement(enclosingElementForLocal)
                }
                else if (runReadAction { actualElement.isObjectLiteral() }) {
                    getOuterClassNamesForElement(actualElement.parentInReadAction)
                }
                else { // Guaranteed to be non-local class or object
                    actualElement.readAction {
                        if (it is KtClass && it.isInterface())
                            ComputedClassNames.Cached(listOfNonNull(getNameForNonLocalClass(it, handleDefaultImpls = true),
                                          getNameForNonLocalClass(it, handleDefaultImpls = false)))
                        else
                            getNameForNonLocalClass(it)?.let { ComputedClassNames.Cached(it) } ?: ComputedClassNames.EMPTY
                    }
                }
            }
            is KtProperty -> {
                if (runReadAction { actualElement.isTopLevel }) {
                    return getOuterClassNamesForElement(actualElement.parentInReadAction)
                }

                val enclosingElementForLocal = runReadAction { KtPsiUtil.getEnclosingElementForLocalDeclaration(actualElement) }
                if (enclosingElementForLocal != null) {
                    return getOuterClassNamesForElement(enclosingElementForLocal)
                }

                val containingClassOrFile = runReadAction {
                    PsiTreeUtil.getParentOfType(actualElement, KtFile::class.java, KtClassOrObject::class.java)
                }

                if (containingClassOrFile is KtObjectDeclaration && runReadAction { containingClassOrFile.isCompanion() }) {
                    // Properties from the companion object can be placed in the companion object's containing class
                    return (getOuterClassNamesForElement(containingClassOrFile.parentInReadAction) +
                            getOuterClassNamesForElement(containingClassOrFile)).distinct()
                }

                if (containingClassOrFile != null)
                    getOuterClassNamesForElement(containingClassOrFile)
                else
                    getOuterClassNamesForElement(actualElement.parentInReadAction)
            }
            is KtNamedFunction -> {
                val typeMapper = KotlinDebuggerCaches.getOrCreateTypeMapper(actualElement)
                val descriptor = typeMapper.bindingContext.get(BindingContext.DECLARATION_TO_DESCRIPTOR, element)

                val classNamesOfContainingDeclaration = getOuterClassNamesForElement(actualElement.parentInReadAction)
                val nonInlineClasses = if (runReadAction { actualElement.isLocal }
                                        || isFunctionWithSuspendStateMachine(descriptor, typeMapper.bindingContext)) {
                    classNamesOfContainingDeclaration + ComputedClassNames.Cached(
                            asmTypeForAnonymousClass(typeMapper.bindingContext, actualElement).internalName.toJdiName())
                } else {
                    classNamesOfContainingDeclaration
                }

                if (!findInlineUseSites) {
                    return nonInlineClasses
                }

                val inlineCallSiteClasses = inlineUsagesSearcher.findInlinedCalls(
                        actualElement,
                        KotlinDebuggerCaches.getOrCreateTypeMapper(actualElement).bindingContext
                ) { this.getOuterClassNamesForElement(it) }

                nonInlineClasses + inlineCallSiteClasses
            }
            is KtAnonymousInitializer -> {
                val initializerOwner = runReadAction { actualElement.containingDeclaration }

                if (initializerOwner is KtObjectDeclaration && runReadAction { initializerOwner.isCompanion() }) {
                    return getOuterClassNamesForElement(runReadAction { initializerOwner.containingClassOrObject })
                }

                return getOuterClassNamesForElement(initializerOwner)
            }
            is KtFunctionLiteral -> {
                val typeMapper = KotlinDebuggerCaches.getOrCreateTypeMapper(actualElement)

                val nonInlinedLambdaClassName = runReadAction {
                    asmTypeForAnonymousClass(typeMapper.bindingContext, actualElement).internalName.toJdiName()
                }

                if (!alwaysReturnLambdaParentClass && !InlineUtil.isInlinedArgument(actualElement, typeMapper.bindingContext, true)) {
                    return ComputedClassNames.Cached(nonInlinedLambdaClassName)
                }

                return getOuterClassNamesForElement(actualElement.parentInReadAction) + ComputedClassNames.Cached(nonInlinedLambdaClassName)
            }
            else -> error("Unexpected element type ${element::class.java.name}")
        }
    }

    private fun isFunctionWithSuspendStateMachine(descriptor: DeclarationDescriptor?, bindingContext: BindingContext): Boolean {
        return descriptor is SimpleFunctionDescriptor && descriptor.isSuspend && descriptor.containsNonTailSuspensionCalls(bindingContext)
    }

    private inline fun <T: Any> doGetClassesForPosition(
            position: SourcePosition,
            transformer: (className: String, lineNumber: Int) -> List<T?>
    ): List<T> {
        val line = position.line
        val outerClassNames = getOuterClassNamesForElement(runReadAction { position.elementAt })
        val resultList = outerClassNames.classNames.flatMap { transformer(it, line) }

        val result = resultList.filterNotNullTo(mutableSetOf())

        for (lambda in position.readAction(::getLambdasAtLineIfAny)) {
            getOuterClassNamesForElement(lambda).classNames.flatMap { transformer(it, line) }.filterNotNullTo(result)
        }

        return result.toList()
    }
}

private fun <T: Any> listOfNonNull(first: T?, second: T?): List<T> {
    return when {
        first != null && second != null -> listOf(first, second)
        first != null -> Collections.singletonList(first)
        second != null -> Collections.singletonList(second)
        else -> Collections.emptyList()
    }
}

private val PsiElement.parentInReadAction
    get() = runReadAction { this.parent }

private fun String.toJdiName() = replace('/', '.')

// Should be run inside a read action
private fun getNameForNonLocalClass(classOrObject: KtClassOrObject, handleDefaultImpls: Boolean = true): String? {
    val simpleName = classOrObject.name ?: return null

    val containingClass = PsiTreeUtil.getParentOfType(classOrObject, KtClassOrObject::class.java, true)
    val containingClassName = containingClass?.let {
        getNameForNonLocalClass(
                containingClass,
                !(containingClass is KtClass && classOrObject is KtObjectDeclaration && classOrObject.isCompanion())
        ) ?: return null
    }

    val packageFqName = classOrObject.containingKtFile.packageFqName.asString()
    val selfName = if (containingClassName != null) "$containingClassName$$simpleName" else simpleName
    val selfNameWithPackage = if (packageFqName.isEmpty() || containingClassName != null) selfName else "$packageFqName.$selfName"

    return if (handleDefaultImpls && classOrObject is KtClass && classOrObject.isInterface())
        selfNameWithPackage + JvmAbi.DEFAULT_IMPLS_SUFFIX
    else
        selfNameWithPackage
}

private fun DebugProcess.findTargetClasses(outerClass: ReferenceType, lineAt: Int): List<ReferenceType> {
    val vmProxy = virtualMachineProxy
    if (!outerClass.isPrepared) return emptyList()

    val targetClasses = ArrayList<ReferenceType>(1)

    try {
        for (location in outerClass.allLineLocations()) {
            val locationLine = location.lineNumber() - 1
            if (locationLine < 0) {
                // such locations are not correspond to real lines in code
                continue
            }

            val method = location.method()
            if (method == null || DebuggerUtils.isSynthetic(method) || method.isBridge) {
                // skip synthetic methods
                continue
            }

            if (lineAt == locationLine) {
                targetClasses += outerClass
            }
        }

        val nestedTypes = vmProxy.nestedTypes(outerClass)
        for (nested in nestedTypes) {
            targetClasses += findTargetClasses(nested, lineAt)
        }
    }
    catch (_: AbsentInformationException) {}

    return targetClasses
}