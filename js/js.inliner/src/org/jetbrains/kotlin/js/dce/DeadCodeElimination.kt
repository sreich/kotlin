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

package org.jetbrains.kotlin.js.dce

import org.jetbrains.kotlin.js.backend.ast.*
import org.jetbrains.kotlin.js.inline.util.collectDefinedNames
import org.jetbrains.kotlin.js.inline.util.collectLocalVariables
import org.jetbrains.kotlin.js.translate.context.Namer
import org.jetbrains.kotlin.js.translate.utils.jsAstUtils.array
import org.jetbrains.kotlin.js.translate.utils.jsAstUtils.index

class DeadCodeElimination(val root: JsNode) {
    private val globalScope = FqnRoot()
    private val nodes = mutableMapOf<JsName, FqnRoot>()
    private var returnNodes = mutableListOf<Fqn>()
    private val fqnMap = mutableMapOf<JsNode, Fqn>()
    private val fqnToExpressions = mutableMapOf<Fqn, MutableSet<JsExpression>>()
    private val usedFqns = mutableSetOf<Fqn>()
    private val usedFqnsWithAncestors = mutableSetOf<Fqn>()
    private val dependencies = mutableMapOf<Fqn, MutableSet<Fqn>>()
    private val functionsToEnter = mutableSetOf<JsFunction>()
    private val nodesToSkip = mutableSetOf<JsNode>()
    private val fqnsWithSideEffects = mutableSetOf<Fqn>()
    private val childFqnMap = mutableMapOf<Fqn, MutableSet<Fqn>>()

    fun apply() {
        addLocalVars(collectDefinedNames(root))
        root.accept(visitor)

        for ((fqn, expressions) in fqnToExpressions.toList()) {
            for (altFqn in fqn.collectRootFqNames()) {
                fqnToExpressions.add(altFqn, *expressions.toTypedArray())
            }
        }
        for (fqn in fqnsWithSideEffects.toList()) {
            fqnsWithSideEffects += fqn.collectRootFqNames()
        }
        for ((fqn, fqnDeps) in dependencies.toList()) {
            fqnDeps += fqnDeps.toList().flatMap { it.collectRootFqNames() }
            for (altFqn in fqn.collectRootFqNames()) {
                dependencies.add(altFqn, *fqnDeps.flatMap { it.collectRootFqNames() }.toTypedArray())
            }
        }

        for (fqn in fqnMap.values.flatMap { it.collectRootFqNames() }.filter { !it.isRoot }) {
            var currentFqn = fqn
            while (!currentFqn.isRoot) {
                val parent = currentFqn.parent()
                if (!childFqnMap.add(parent, currentFqn)) break
                currentFqn = parent
            }
        }

        root.accept(usageFinder)
        for (fqn in usedFqns) {
            println(fqn)
        }

        eliminator.accept(root)
    }

    private fun addLocalVars(names: Collection<JsName>) {
        nodes += names.filter { it !in nodes }.associate { varName -> varName to FqnRoot().apply { addLocalNames(varName) } }
    }

    private val visitor = object : JsVisitor() {
        override fun visitVars(x: JsVars) {
            x.vars.forEach { accept(it) }
        }

        override fun visit(x: JsVars.JsVar) {
            val rhs = x.initExpression
            if (rhs != null) {
                processAssignment(x.name.makeRef(), rhs)?.let { fqnMap[x] = it }
            }
        }

        override fun visitExpressionStatement(x: JsExpressionStatement) {
            val expression = x.expression
            if (expression is JsBinaryOperation) {
                if (expression.operator == JsBinaryOperator.ASG) {
                    processAssignment(expression.arg1, expression.arg2)?.let {
                        fqnMap[x] = it
                    }
                }
            }
            else if (expression is JsFunction) {
                expression.name?.let { nodes[it] }?.let {
                    val fqn = Fqn(it, emptyList())
                    fqnMap[x] = fqn
                    fqnToExpressions.add(fqn, expression)
                }
            }
            else if (expression is JsInvocation) {
                val function = expression.qualifier
                if (function is JsFunction) {
                    enterFunction(function, expression.arguments)
                }
                else {
                    val fqn = extractFqn(function)
                    if (fqn != null) {
                        val path = fqn.path.joinToString(".")
                        if (fqn.root == globalScope) {
                            when (path) {
                                "Object.defineProperty" -> handleObjectDefineProperty(
                                        x,
                                        expression.arguments.getOrNull(0),
                                        expression.arguments.getOrNull(1),
                                        expression.arguments.getOrNull(2))
                            }
                        }
                        else if (fqn.root.localNames.any { it.ident == "Kotlin" }) {
                            when (path) {
                                "defineModule" -> {
                                    nodesToSkip += x
                                }
                            }
                        }
                    }
                }
            }
        }

        private fun handleObjectDefineProperty(statement: JsStatement, target: JsExpression?, propertyName: JsExpression?,
                                               propertyDescriptor: JsExpression?) {
            if (target == null || propertyName !is JsStringLiteral || propertyDescriptor == null) return
            val targetFqn = extractFqn(target) ?: return

            fqnMap[statement] = targetFqn.child(propertyName.value)
            fqnsWithSideEffects += targetFqn.child(propertyName.value)
            if (propertyDescriptor is JsObjectLiteral) {
                for (initializer in propertyDescriptor.propertyInitializers) {
                    processAssignment(JsNameRef(propertyName.value, target), initializer.valueExpr)
                }
            }
            else if (propertyDescriptor is JsInvocation) {
                val function = propertyDescriptor.qualifier
                val functionFqn = extractFqn(function)
                if (functionFqn != null && functionFqn.root == globalScope &&
                    functionFqn.path == listOf("Object", "getOwnPropertyDescriptor")
                ) {
                    val source = propertyDescriptor.arguments.getOrNull(0)
                    val sourcePropertyName = propertyDescriptor.arguments.getOrNull(1)
                    if (source != null && sourcePropertyName is JsStringLiteral) {
                        processAssignment(JsNameRef(propertyName.value, target), JsNameRef(sourcePropertyName.value, source))
                    }
                }
            }
        }

        override fun visitBlock(x: JsBlock) {
            x.statements.forEach { accept(it) }
        }

        override fun visitIf(x: JsIf) {
            accept(x.thenStatement)
            x.elseStatement?.accept(this)
        }

        override fun visitReturn(x: JsReturn) {
            val expr = x.expression
            if (expr != null) {
                extractFqn(expr)?.let {
                    returnNodes.add(it)
                    fqnMap[x] = it
                }
            }
        }

        private fun processAssignment(lhs: JsExpression, rhs: JsExpression): Fqn? {
            val leftFqn = extractFqn(lhs)
            val rightFqn = extractFqn(rhs)

            if (leftFqn != null && rightFqn != null) {
                if (leftFqn.path.isEmpty() && rightFqn.path.isEmpty()) {
                    leftFqn.root.union(rightFqn.root)
                    return leftFqn
                }
                else if (leftFqn.path.isEmpty()) {
                    leftFqn.root.addQualifiers(rightFqn)
                    return rightFqn
                }
                else if (rightFqn.path.isEmpty()) {
                    rightFqn.root.addQualifiers(leftFqn)
                    return leftFqn
                }
                else {
                    addDependency(leftFqn, rightFqn)
                    addDependency(rightFqn, leftFqn)
                }
            }
            else if (leftFqn != null) {
                if (rhs is JsInvocation) {
                    val function = rhs.qualifier
                    if (function is JsFunction) {
                        enterFunction(function, rhs.arguments)
                        return null
                    }
                    else {
                        val fqn = extractFqn(function)
                        if (fqn != null && fqn.root == globalScope) {
                            val path = fqn.path.joinToString(".")
                            when (path) {
                                "Object.create" -> {
                                    handleObjectCreate(leftFqn, rhs.arguments.getOrNull(0))
                                    return leftFqn
                                }
                            }
                        }
                    }
                }
                else if (rhs is JsBinaryOperation) {
                    if (rhs.operator == JsBinaryOperator.OR) {
                        val secondFqn = extractFqn(rhs.arg1)
                        val reassignment = rhs.arg2
                        if (reassignment is JsBinaryOperation && reassignment.operator == JsBinaryOperator.ASG) {
                            val reassignFqn = extractFqn(reassignment.arg1)
                            val reassignValue = reassignment.arg2
                            if (reassignFqn == secondFqn && reassignFqn != null && reassignValue is JsObjectLiteral &&
                                reassignValue.propertyInitializers.isEmpty()
                            ) {
                                return processAssignment(lhs, rhs.arg1)
                            }
                        }
                    }
                    else if (rhs.operator == JsBinaryOperator.COMMA) {
                        if (rhs.arg1 is JsStringLiteral) {
                            return processAssignment(lhs, rhs.arg2)
                        }
                    }
                }
                else if (rhs is JsFunction) {
                    fqnToExpressions.add(leftFqn, rhs)
                    return leftFqn
                }
                else if (leftFqn.path.lastOrNull() == Namer.METADATA) {
                    fqnToExpressions.add(leftFqn, rhs)
                    return leftFqn
                }
                else if (rhs is JsObjectLiteral && rhs.propertyInitializers.isEmpty()) {
                    return leftFqn
                }
            }
            return null
        }

        private fun handleObjectCreate(target: Fqn, arg: JsExpression?) {
            if (arg == null) return

            val prototypeFqn = extractFqn(arg) ?: return
            addDependency(target, prototypeFqn)
            fqnToExpressions.add(target, arg)
        }

        private fun enterFunction(function: JsFunction, arguments: List<JsExpression>): List<Fqn> {
            functionsToEnter += function
            addLocalVars(function.collectLocalVariables())

            for ((param, arg) in function.parameters.zip(arguments)) {
                processAssignment(param.name.makeRef(), arg)
            }
            val oldReturnNodes = returnNodes
            val newReturnNodes = mutableListOf<Fqn>()
            returnNodes = newReturnNodes

            accept(function.body)

            returnNodes = oldReturnNodes
            return newReturnNodes
        }

        private fun addDependency(from: Fqn, to: Fqn) {
            dependencies.add(from, to)
        }
    }

    private val usageFinder = object : RecursiveJsVisitor() {
        override fun visit(x: JsVars.JsVar) {
            if (fqnMap[x] == null && x !in nodesToSkip) {
                super.visit(x)
            }
        }

        override fun visitExpressionStatement(x: JsExpressionStatement) {
            if (fqnMap[x] == null && x !in nodesToSkip) {
                super.visitExpressionStatement(x)
            }
        }

        override fun visitNameRef(nameRef: JsNameRef) {
            val fqn = extractFqn(nameRef)
            if (fqn != null) {
                for (rootFqn in fqn.collectRootFqNames()) {
                    use(rootFqn)
                }
            }
            else {
                super.visitNameRef(nameRef)
            }
        }

        override fun visitInvocation(invocation: JsInvocation) {
            val function = invocation.qualifier
            if (function is JsFunction && function in functionsToEnter) {
                accept(function.body)
            }
            else {
                super.visitInvocation(invocation)
            }
        }

        private fun use(fqn: Fqn) {
            if (!usedFqns.add(fqn)) return

            fqnToExpressions[fqn]?.forEach { expr ->
                if (expr is JsFunction) {
                    addLocalVars(expr.collectLocalVariables())
                    expr.body.accept(this)
                }
                else {
                    expr.accept(this)
                }
            }
            useAncestors(fqn)
            dependencies[fqn]?.forEach { use(it) }
            childFqnMap[fqn]?.forEach { use(it) }
        }

        private fun useAncestors(fqn: Fqn) {
            if (fqn in fqnsWithSideEffects && fqn !in usedFqns) {
                use(fqn)
            }
            else if (usedFqnsWithAncestors.add(fqn)) {
                fqnToExpressions[fqn]?.forEach { expr ->
                    if (expr !is JsFunction) {
                        expr.accept(this)
                    }
                }
                if (!fqn.isRoot) {
                    useAncestors(fqn.parent())
                }
            }
        }

        override fun visitPrefixOperation(x: JsPrefixOperation) {
            if (x.operator == JsUnaryOperator.TYPEOF) {
                val arg = x.arg
                if (arg is JsNameRef && arg.qualifier == null) return
            }
            super.visitPrefixOperation(x)
        }

        override fun visitFunction(x: JsFunction) {
            if (x in functionsToEnter) {
                super.visitFunction(x)
            }
        }
    }

    private val eliminator = object : JsVisitorWithContextImpl() {
        override fun visit(x: JsVars.JsVar, ctx: JsContext<*>): Boolean = removeIfNecessary(x, ctx)

        override fun visit(x: JsExpressionStatement, ctx: JsContext<*>): Boolean = removeIfNecessary(x, ctx)

        private fun removeIfNecessary(x: JsNode, ctx: JsContext<*>): Boolean {
            if (x in nodesToSkip) {
                ctx.removeMe()
                return false
            }
            val fqn = fqnMap[x]
            return if (!isUsed(fqn)) {
                ctx.removeMe()
                false
            }
            else {
                true
            }
        }

        override fun endVisit(x: JsVars, ctx: JsContext<*>) {
            if (x.vars.isEmpty()) {
                ctx.removeMe()
            }
        }
    }

    private fun isUsed(fqn: Fqn?): Boolean = fqn == null || fqn in usedFqnsWithAncestors ||
                                             fqn.collectRootFqNames().any { it in usedFqnsWithAncestors }

    private fun extractFqn(expression: JsExpression): Fqn? {
        val path = mutableListOf<String>()
        val rootNode = extractRootNode(expression, path)
        return if (rootNode != null) Fqn(rootNode, path.asReversed()) else null
    }

    private fun extractRootNode(expression: JsExpression, pathConsumer: MutableList<String>): FqnRoot? {
        return when (expression) {
            is JsNameRef -> {
                val qualifier = expression.qualifier
                if (qualifier == null) {
                    expression.name?.let { nodes[it] } ?: let {
                        pathConsumer += expression.ident
                        globalScope
                    }
                }
                else {
                    pathConsumer += expression.ident
                    extractRootNode(qualifier, pathConsumer)
                }
            }
            is JsArrayAccess -> {
                val index = expression.index
                if (index is JsStringLiteral) {
                    pathConsumer += index.value
                    extractRootNode(expression.array, pathConsumer)
                }
                else {
                    null
                }
            }
            else -> {
                null
            }
        }
    }

    private class FqnRoot {
        private val localNamesImpl = mutableSetOf<JsName>()
        private val qualifiersImpl = mutableSetOf<Fqn>()
        private var parent = this
        private var rank = 0

        val original: FqnRoot
            get() {
                if (parent == this) return this
                parent = parent.original
                return parent
            }

        fun union(other: FqnRoot) {
            val a = original
            val b = other.original
            if (a == b) return
            if (a.rank < b.rank) {
                a.parent = b
                b.merge(a)
            }
            else {
                b.parent = a
                a.merge(b)
            }

            if (a.rank == b.rank) {
                a.rank++
            }
        }

        private fun merge(other: FqnRoot) {
            localNamesImpl += other.localNamesImpl
            qualifiersImpl += other.qualifiersImpl
        }

        val localNames: Set<JsName>
            get() = original.localNamesImpl

        fun addLocalNames(vararg names: JsName) {
            original.localNamesImpl += names
        }

        fun addQualifiers(vararg qualifiers: Fqn) {
            original.qualifiersImpl += qualifiers
        }

        fun collectRootFqNames(): Set<Fqn> {
            val result = mutableSetOf<Fqn>()
            return if (collectRootFqNamesImpl(emptyList(), result, mutableSetOf())) {
                result
            }
            else {
                setOf(Fqn(this, emptyList()))
            }
        }

        private fun collectRootFqNamesImpl(path: List<String>, result: MutableSet<Fqn>, visited: MutableSet<FqnRoot>): Boolean {
            val original = this.original
            if (!visited.add(original)) {
                return false
            }

            if (original.qualifiersImpl.isEmpty()) {
                result += Fqn(original, path)
            }
            else {
                for ((root, qualifierPath) in original.qualifiersImpl) {
                    if (!root.collectRootFqNamesImpl(qualifierPath + path, result, visited)) {
                        result += Fqn(root, qualifierPath)
                    }
                }
            }

            return true
        }
    }

    private data class Fqn(val root: FqnRoot, val path: List<String>) {
        fun collectRootFqNames(): Set<Fqn> = root.collectRootFqNames()
                .map { (baseRoot, basePath) -> Fqn(baseRoot, basePath + path) }
                .toSet()

        override fun toString(): String = (root.localNames.firstOrNull()?.ident ?: "<unknown>") + path.joinToString("") { ".$it" }

        fun child(name: String): Fqn = Fqn(root, path + name)

        fun parent(): Fqn = Fqn(root, path.dropLast(1))

        val isRoot: Boolean get() = path.isEmpty()
    }

    private fun <K, V> MutableMap<K, MutableSet<V>>.add(key: K, vararg values: V): Boolean = getOrPut(key, ::mutableSetOf).addAll(values)
}