// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.groovy.lang.typing

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.*
import org.jetbrains.plugins.groovy.lang.psi.api.GroovyMethodResult
import org.jetbrains.plugins.groovy.lang.psi.api.GroovyResolveResult
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrField
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrMethodCall
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrAccessorMethod
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrMethod
import org.jetbrains.plugins.groovy.lang.psi.impl.GrClosureType
import org.jetbrains.plugins.groovy.lang.psi.impl.GrLiteralClassType
import org.jetbrains.plugins.groovy.lang.psi.impl.signatures.GrClosureSignatureUtil
import org.jetbrains.plugins.groovy.lang.psi.impl.statements.expressions.TypesUtil
import org.jetbrains.plugins.groovy.lang.psi.util.PsiUtil
import org.jetbrains.plugins.groovy.lang.psi.util.PsiUtil.getSmartReturnType
import org.jetbrains.plugins.groovy.lang.resolve.api.Argument
import org.jetbrains.plugins.groovy.lang.resolve.api.Arguments
import org.jetbrains.plugins.groovy.lang.resolve.impl.getArguments

class DefaultMethodCallTypeCalculator : GrTypeCalculator<GrMethodCall> {

  override fun getType(expression: GrMethodCall): PsiType? {
    val results = expression.multiResolve(false)
    if (results.isEmpty()) {
      return null
    }
    val arguments = expression.getArguments()
    var type: PsiType? = null
    for (result in results) {
      type = TypesUtil.getLeastUpperBoundNullable(type, getTypeFromResult(result, arguments, expression), expression.manager)
    }
    return type
  }
}

fun getTypeFromResult(result: GroovyResolveResult, arguments: Arguments?, context: GrExpression): PsiType? {
  val baseType = getBaseTypeFromResult(result, arguments, context).devoid(context) ?: return null
  val substitutor = if (baseType !is GrLiteralClassType && hasGenerics(baseType)) result.substitutor else PsiSubstitutor.EMPTY
  return TypesUtil.substituteAndNormalizeType(baseType, substitutor, result.spreadState, context)
}

private fun getBaseTypeFromResult(result: GroovyResolveResult, arguments: Arguments?, context: PsiElement): PsiType? {
  return when {
    result.isInvokedOnProperty -> getTypeFromPropertyCall(result.element, arguments, context)
    result is GroovyMethodResult -> getTypeFromCandidate(result, context)
    else -> null
  }
}

private fun getTypeFromCandidate(result: GroovyMethodResult, context: PsiElement): PsiType? {
  val candidate = result.candidate ?: return null
  for (ext in ep.extensions) {
    return ext.getType(candidate.receiver, candidate.method, candidate.argumentMapping?.arguments, context) ?: continue
  }
  return getSmartReturnType(candidate.method)
}

private val ep: ExtensionPointName<GrCallTypeCalculator> = ExtensionPointName.create("org.intellij.groovy.callTypeCalculator")

private fun getTypeFromPropertyCall(element: PsiElement?, arguments: Arguments?, context: PsiElement): PsiType? {
  val type = when (element) { // TODO introduce property concept, resolve into it and get its type
    is GrField -> element.typeGroovy
    is GrMethod -> element.inferredReturnType
    is GrAccessorMethod -> element.inferredReturnType
    is PsiField -> element.type
    is PsiMethod -> element.returnType
    else -> null
  }
  if (type !is GrClosureType) {
    return null
  }
  val argumentTypes = arguments?.map(Argument::type)?.toTypedArray()
  return GrClosureSignatureUtil.getReturnType(type.signatures, argumentTypes, context)
}

fun PsiType?.devoid(context: PsiElement): PsiType? {
  return if (this == PsiType.VOID && !PsiUtil.isCompileStatic(context)) PsiType.NULL else this
}

private fun hasGenerics(type: PsiType): Boolean {
  return !Registry.`is`("groovy.return.type.optimization") || type.accept(GenericsSearcher) == true
}

private object GenericsSearcher : PsiTypeVisitor<Boolean?>() {

  override fun visitClassType(classType: PsiClassType): Boolean? {
    if (classType.resolve() is PsiTypeParameter) {
      return true
    }
    if (!classType.hasParameters()) {
      return null
    }
    for (parameter in classType.parameters) {
      if (parameter.accept(this) == true) return true
    }
    return null
  }

  override fun visitArrayType(arrayType: PsiArrayType): Boolean? = arrayType.componentType.accept(this)

  override fun visitWildcardType(wildcardType: PsiWildcardType): Boolean? = wildcardType.bound?.accept(this)
}
