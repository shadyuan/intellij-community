// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.refactoring.introduce.extractionEngine

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.openapi.util.Key
import com.intellij.profile.codeInspection.ProjectInspectionProfileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.BaseRefactoringProcessor
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.builtins.isFunctionType
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.idea.base.codeInsight.KotlinNameSuggestionProvider
import org.jetbrains.kotlin.idea.base.fe10.codeInsight.newDeclaration.Fe10KotlinNameSuggester
import org.jetbrains.kotlin.idea.base.fe10.codeInsight.newDeclaration.Fe10KotlinNewDeclarationNameValidator
import org.jetbrains.kotlin.idea.base.psi.isMultiLine
import org.jetbrains.kotlin.idea.base.psi.replaced
import org.jetbrains.kotlin.idea.base.psi.unifier.KotlinPsiRange
import org.jetbrains.kotlin.idea.base.psi.unifier.KotlinPsiUnificationResult
import org.jetbrains.kotlin.idea.base.psi.unifier.KotlinPsiUnificationResult.StrictSuccess
import org.jetbrains.kotlin.idea.base.psi.unifier.KotlinPsiUnificationResult.WeakSuccess
import org.jetbrains.kotlin.idea.base.psi.unifier.toRange
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.core.ShortenReferences
import org.jetbrains.kotlin.idea.core.appendElement
import org.jetbrains.kotlin.idea.core.moveInsideParenthesesAndReplaceWith
import org.jetbrains.kotlin.idea.core.toVisibility
import org.jetbrains.kotlin.idea.inspections.PublicApiImplicitTypeInspection
import org.jetbrains.kotlin.idea.inspections.UseExpressionBodyInspection
import org.jetbrains.kotlin.idea.intentions.InfixCallToOrdinaryIntention
import org.jetbrains.kotlin.idea.intentions.RemoveExplicitTypeArgumentsIntention
import org.jetbrains.kotlin.idea.refactoring.intentions.OperatorToFunctionConverter
import org.jetbrains.kotlin.idea.refactoring.introduce.*
import org.jetbrains.kotlin.idea.refactoring.introduce.extractionEngine.OutputValue.*
import org.jetbrains.kotlin.idea.refactoring.introduce.extractionEngine.OutputValueBoxer.AsTuple
import org.jetbrains.kotlin.idea.refactoring.removeTemplateEntryBracesIfPossible
import org.jetbrains.kotlin.idea.search.usagesSearch.descriptor
import org.jetbrains.kotlin.idea.util.IdeDescriptorRenderers
import org.jetbrains.kotlin.idea.util.getAllAccessibleVariables
import org.jetbrains.kotlin.idea.util.getResolutionScope
import org.jetbrains.kotlin.idea.util.psi.patternMatching.KotlinPsiUnifier
import org.jetbrains.kotlin.idea.util.psi.patternMatching.UnifierParameter
import org.jetbrains.kotlin.idea.util.psi.patternMatching.match
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.KtPsiFactory.CallableBuilder
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.renderer.render
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.calls.util.getCalleeExpressionIfAny
import org.jetbrains.kotlin.resolve.checkers.OptInNames
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.isError
import org.jetbrains.kotlin.types.isFlexible
import java.util.*

private fun buildSignature(config: ExtractionGeneratorConfiguration, renderer: DescriptorRenderer): CallableBuilder {
    val extractionTarget = config.generatorOptions.target
    if (!extractionTarget.isAvailable(config.descriptor)) {
        val message = KotlinBundle.message("error.text.can.t.generate.0.1",
            extractionTarget.targetName,
            config.descriptor.extractionData.codeFragmentText
        )
        throw BaseRefactoringProcessor.ConflictsInTestsException(listOf(message))
    }

    val builderTarget = when (extractionTarget) {
        ExtractionTarget.FUNCTION, ExtractionTarget.FAKE_LAMBDALIKE_FUNCTION -> CallableBuilder.Target.FUNCTION
        else -> CallableBuilder.Target.READ_ONLY_PROPERTY
    }
    return CallableBuilder(builderTarget).apply {
        val visibility = config.descriptor.visibility?.value ?: ""

        fun TypeParameter.isReified() = originalDeclaration.hasModifier(KtTokens.REIFIED_KEYWORD)
        val shouldBeInline = config.descriptor.typeParameters.any { it.isReified() }

        val optInAnnotation = if (config.generatorOptions.target != ExtractionTarget.FUNCTION || config.descriptor.optInMarkers.isEmpty()) {
            ""
        } else {
            val innerText = config.descriptor.optInMarkers.joinToString(separator = ", ") { "${it.shortName().render()}::class" }
            "@${OptInNames.OPT_IN_FQ_NAME.shortName().render()}($innerText)\n"
        }

        val annotations = if (config.descriptor.annotations.isEmpty()) {
            ""
        } else {
            config.descriptor.annotations.joinToString(separator = "\n", postfix = "\n") { renderer.renderAnnotation(it) }
        }
        val extraModifiers = config.descriptor.modifiers.map { it.value } +
                listOfNotNull(if (shouldBeInline) KtTokens.INLINE_KEYWORD.value else null) +
                listOfNotNull(if (config.generatorOptions.isConst) KtTokens.CONST_KEYWORD.value else null)
        val modifiers = if (visibility.isNotEmpty()) listOf(visibility) + extraModifiers else extraModifiers
        modifier(annotations + optInAnnotation + modifiers.joinToString(separator = " "))

        typeParams(
            config.descriptor.typeParameters.map {
                val typeParameter = it.originalDeclaration
                val bound = typeParameter.extendsBound

                buildString {
                    if (it.isReified()) {
                        append(KtTokens.REIFIED_KEYWORD.value)
                        append(' ')
                    }
                    append(typeParameter.name)
                    if (bound != null) {
                        append(" : ")
                        append(bound.text)
                    }
                }
            }
        )

        fun KotlinType.typeAsString() = renderer.renderType(this)

        config.descriptor.receiverParameter?.let {
            val receiverType = it.parameterType
            val receiverTypeAsString = receiverType.typeAsString()
            receiver(if (receiverType.isFunctionType) "($receiverTypeAsString)" else receiverTypeAsString)
        }

        name(config.generatorOptions.dummyName ?: config.descriptor.name)

        config.descriptor.parameters.forEach { parameter ->
            param(parameter.name, parameter.parameterType.typeAsString())
        }

        with(config.descriptor.returnType) {
            if (KotlinBuiltIns.isUnit(this) || isError || extractionTarget == ExtractionTarget.PROPERTY_WITH_INITIALIZER) {
                noReturnType()
            } else {
                returnType(typeAsString())
            }
        }

        typeConstraints(config.descriptor.typeParameters.flatMap { it.originalConstraints }.map { it.text!! })
    }
}

fun ExtractionGeneratorConfiguration.getSignaturePreview(renderer: DescriptorRenderer) = buildSignature(this, renderer).asString()

fun ExtractionGeneratorConfiguration.getDeclarationPattern(
    descriptorRenderer: DescriptorRenderer = IdeDescriptorRenderers.SOURCE_CODE
): String {
    val extractionTarget = generatorOptions.target
    if (!extractionTarget.isAvailable(descriptor)) {
        throw BaseRefactoringProcessor.ConflictsInTestsException(
            listOf(
                KotlinBundle.message("error.text.can.t.generate.0.1",
                    extractionTarget.targetName,
                    descriptor.extractionData.codeFragmentText
                )
            )
        )
    }

    return buildSignature(this, descriptorRenderer).let { builder ->
        builder.transform {
            for (i in generateSequence(indexOf('$')) { indexOf('$', it + 2) }) {
                if (i < 0) break
                insert(i + 1, '$')
            }
        }

        when (extractionTarget) {
            ExtractionTarget.FUNCTION,
            ExtractionTarget.FAKE_LAMBDALIKE_FUNCTION,
            ExtractionTarget.PROPERTY_WITH_GETTER -> builder.blockBody("$0")
            ExtractionTarget.PROPERTY_WITH_INITIALIZER -> builder.initializer("$0")
            ExtractionTarget.LAZY_PROPERTY -> builder.lazyBody("$0")
        }

        builder.asString()
    }
}

fun KotlinType.isSpecial(): Boolean {
    val classDescriptor = this.constructor.declarationDescriptor as? ClassDescriptor ?: return false
    return classDescriptor.name.isSpecial || DescriptorUtils.isLocal(classDescriptor)
}

fun createNameCounterpartMap(from: KtElement, to: KtElement): Map<KtSimpleNameExpression, KtSimpleNameExpression> {
    return from.collectDescendantsOfType<KtSimpleNameExpression>().zip(to.collectDescendantsOfType<KtSimpleNameExpression>()).toMap()
}

class DuplicateInfo(
    val range: KotlinPsiRange,
    val controlFlow: ControlFlow,
    val arguments: List<String>
)

fun ExtractableCodeDescriptor.findDuplicates(): List<DuplicateInfo> {
    fun processWeakMatch(match: WeakSuccess<*>, newControlFlow: ControlFlow): Boolean {
        val valueCount = controlFlow.outputValues.size

        val weakMatches = HashMap(match.weakMatches)
        val currentValuesToNew = HashMap<OutputValue, OutputValue>()

        fun matchValues(currentValue: OutputValue, newValue: OutputValue): Boolean {
            if ((currentValue is Jump) != (newValue is Jump)) return false
            if (currentValue.originalExpressions.zip(newValue.originalExpressions).all { weakMatches[it.first] == it.second }) {
                currentValuesToNew[currentValue] = newValue
                weakMatches.keys.removeAll(currentValue.originalExpressions)
                return true
            }
            return false
        }

        if (valueCount == 1) {
            matchValues(controlFlow.outputValues.first(), newControlFlow.outputValues.first())
        } else {
            outer@
            for (currentValue in controlFlow.outputValues)
                for (newValue in newControlFlow.outputValues) {
                    if ((currentValue is ExpressionValue) != (newValue is ExpressionValue)) continue
                    if (matchValues(currentValue, newValue)) continue@outer
                }
        }

        return currentValuesToNew.size == valueCount && weakMatches.isEmpty()
    }

    fun getControlFlowIfMatched(match: KotlinPsiUnificationResult.Success<*>): ControlFlow? {
        val analysisResult = extractionData.copy(originalRange = match.range).performAnalysis()
        if (analysisResult.status != AnalysisResult.Status.SUCCESS) return null

        val newControlFlow = analysisResult.descriptor!!.controlFlow
        if (newControlFlow.outputValues.isEmpty()) return newControlFlow
        if (controlFlow.outputValues.size != newControlFlow.outputValues.size) return null

        val matched = when (match) {
            is StrictSuccess -> true
            is WeakSuccess -> processWeakMatch(match, newControlFlow)
            else -> throw AssertionError("Unexpected unification result: $match")
        }

        return if (matched) newControlFlow else null
    }

    val unifierParameters = parameters.map { UnifierParameter(it.originalDescriptor, it.parameterType) }

    val unifier = KotlinPsiUnifier(unifierParameters, true)

    val scopeElement = getOccurrenceContainer() ?: return Collections.emptyList()
    val originalTextRange = extractionData.originalRange.getPhysicalTextRange()
    return extractionData
        .originalRange
        .match(scopeElement, unifier)
        .asSequence()
        .filter { !(it.range.getPhysicalTextRange().intersects(originalTextRange)) }
        .mapNotNull { match ->
            val controlFlow = getControlFlowIfMatched(match)
            val range = with(match.range) {
                (elements.singleOrNull() as? KtStringTemplateEntryWithExpression)?.expression?.toRange() ?: this
            }

            controlFlow?.let {
                DuplicateInfo(range, it, unifierParameters.map { param ->
                    match.substitution.getValue(param).text!!
                })
            }
        }
        .toList()
}

private fun ExtractableCodeDescriptor.getOccurrenceContainer(): PsiElement? {
    return extractionData.duplicateContainer ?: extractionData.targetSibling.parent
}

private fun makeCall(
    extractableDescriptor: ExtractableCodeDescriptor,
    declaration: KtNamedDeclaration,
    controlFlow: ControlFlow,
    rangeToReplace: KotlinPsiRange,
    arguments: List<String>
) {
    fun insertCall(anchor: PsiElement, wrappedCall: KtExpression): KtExpression? {
        val firstExpression = rangeToReplace.elements.firstOrNull { it is KtExpression } as? KtExpression
        if (firstExpression?.isLambdaOutsideParentheses() == true) {
            val functionLiteralArgument = firstExpression.getStrictParentOfType<KtLambdaArgument>()!!
            return functionLiteralArgument.moveInsideParenthesesAndReplaceWith(wrappedCall, extractableDescriptor.originalContext)
        }

        if (anchor is KtOperationReferenceExpression) {
            val newNameExpression = when (val operationExpression = anchor.parent as? KtOperationExpression ?: return null) {
                is KtUnaryExpression -> OperatorToFunctionConverter.convert(operationExpression).second
                is KtBinaryExpression -> {
                    InfixCallToOrdinaryIntention.Holder.convert(operationExpression).getCalleeExpressionIfAny()
                }
                else -> null
            }
            return newNameExpression?.replaced(wrappedCall)
        }

        (anchor as? KtExpression)?.extractableSubstringInfo?.let {
            return it.replaceWith(wrappedCall)
        }

        return anchor.replaced(wrappedCall)
    }

    if (rangeToReplace.isEmpty) return

    val anchor = rangeToReplace.elements.first()
    val anchorParent = anchor.parent!!

    anchor.nextSibling?.let { from ->
        val to = rangeToReplace.elements.last()
        if (to != anchor) {
            anchorParent.deleteChildRange(from, to)
        }
    }

    val calleeName = declaration.name?.quoteIfNeeded()
    val callText = when (declaration) {
        is KtNamedFunction -> {
            val argumentsText = arguments.joinToString(separator = ", ", prefix = "(", postfix = ")")
            val typeArguments = extractableDescriptor.typeParameters.map { it.originalDeclaration.name }
            val typeArgumentsText = with(typeArguments) {
                if (isNotEmpty()) joinToString(separator = ", ", prefix = "<", postfix = ">") else ""
            }
            "$calleeName$typeArgumentsText$argumentsText"
        }
        else -> calleeName
    }

    val anchorInBlock = generateSequence(anchor) { it.parent }.firstOrNull { it.parent is KtBlockExpression }
    val block = (anchorInBlock?.parent as? KtBlockExpression) ?: anchorParent

    val psiFactory = KtPsiFactory(anchor.project)
    val newLine = psiFactory.createNewLine()

    if (controlFlow.outputValueBoxer is AsTuple && controlFlow.outputValues.size > 1 && controlFlow.outputValues
            .all { it is Initializer }
    ) {
        val declarationsToMerge = controlFlow.outputValues.map { (it as Initializer).initializedDeclaration }
        val isVar = declarationsToMerge.first().isVar
        if (declarationsToMerge.all { it.isVar == isVar }) {
            controlFlow.declarationsToCopy.subtract(declarationsToMerge).forEach {
                block.addBefore(psiFactory.createDeclaration(it.text!!), anchorInBlock) as KtDeclaration
                block.addBefore(newLine, anchorInBlock)
            }

            val entries = declarationsToMerge.map { p -> p.name + (p.typeReference?.let { ": ${it.text}" } ?: "") }
            anchorInBlock?.replace(
                psiFactory.createDestructuringDeclaration("${if (isVar) "var" else "val"} (${entries.joinToString()}) = $callText")
            )

            return
        }
    }

    val inlinableCall = controlFlow.outputValues.size <= 1
    val unboxingExpressions =
        if (inlinableCall) {
            controlFlow.outputValueBoxer.getUnboxingExpressions(callText ?: return)
        } else {
            val varNameValidator = Fe10KotlinNewDeclarationNameValidator(block, anchorInBlock, KotlinNameSuggestionProvider.ValidatorTarget.VARIABLE)
            val resultVal = Fe10KotlinNameSuggester.suggestNamesByType(extractableDescriptor.returnType, varNameValidator, null).first()
            block.addBefore(psiFactory.createDeclaration("val $resultVal = $callText"), anchorInBlock)
            block.addBefore(newLine, anchorInBlock)
            controlFlow.outputValueBoxer.getUnboxingExpressions(resultVal)
        }

    val copiedDeclarations = HashMap<KtDeclaration, KtDeclaration>()
    for (decl in controlFlow.declarationsToCopy) {
        val declCopy = psiFactory.createDeclaration<KtDeclaration>(decl.text!!)
        copiedDeclarations[decl] = block.addBefore(declCopy, anchorInBlock) as KtDeclaration
        block.addBefore(newLine, anchorInBlock)
    }

    if (controlFlow.outputValues.isEmpty()) {
        anchor.replace(psiFactory.createExpression(callText!!))
        return
    }

    fun wrapCall(outputValue: OutputValue, callText: String): List<PsiElement> {
        return when (outputValue) {
            is ExpressionValue -> {
                val exprText = if (outputValue.callSiteReturn) {
                    val firstReturn = outputValue.originalExpressions.asSequence().filterIsInstance<KtReturnExpression>().firstOrNull()
                    val label = firstReturn?.getTargetLabel()?.text ?: ""
                    "return$label $callText"
                } else {
                    callText
                }
                Collections.singletonList(psiFactory.createExpression(exprText))
            }

            is ParameterUpdate ->
                Collections.singletonList(
                    psiFactory.createExpression("${outputValue.parameter.argumentText} = $callText")
                )

            is Jump -> {
                when {
                    outputValue.elementToInsertAfterCall == null -> Collections.singletonList(psiFactory.createExpression(callText))
                    outputValue.conditional -> Collections.singletonList(
                        psiFactory.createExpression("if ($callText) ${outputValue.elementToInsertAfterCall.text}")
                    )
                    else -> listOf(
                        psiFactory.createExpression(callText),
                        newLine,
                        psiFactory.createExpression(outputValue.elementToInsertAfterCall.text!!)
                    )
                }
            }

            is Initializer -> {
                val newProperty = copiedDeclarations[outputValue.initializedDeclaration] as KtProperty
                newProperty.initializer = psiFactory.createExpression(callText)
                Collections.emptyList()
            }

            else -> throw IllegalArgumentException("Unknown output value: $outputValue")
        }
    }

    val defaultValue = controlFlow.defaultOutputValue

    controlFlow.outputValues
        .filter { it != defaultValue }
        .flatMap { wrapCall(it, unboxingExpressions.getValue(it)) }
        .withIndex()
        .forEach {
            val (i, e) = it

            if (i > 0) {
                block.addBefore(newLine, anchorInBlock)
            }
            block.addBefore(e, anchorInBlock)
        }

    defaultValue?.let {
        if (!inlinableCall) {
            block.addBefore(newLine, anchorInBlock)
        }
        insertCall(anchor, wrapCall(it, unboxingExpressions.getValue(it)).first() as KtExpression)?.removeTemplateEntryBracesIfPossible()
    }

    if (anchor.isValid) {
        anchor.delete()
    }
}

private var KtExpression.isJumpElementToReplace: Boolean
        by NotNullablePsiCopyableUserDataProperty(Key.create("IS_JUMP_ELEMENT_TO_REPLACE"), false)

private var KtReturnExpression.isReturnForLabelRemoval: Boolean
        by NotNullablePsiCopyableUserDataProperty(Key.create("IS_RETURN_FOR_LABEL_REMOVAL"), false)

fun ExtractionGeneratorConfiguration.generateDeclaration(
    declarationToReplace: KtNamedDeclaration? = null
): ExtractionResult {
    val psiFactory = KtPsiFactory(descriptor.extractionData.project)

    fun getReturnsForLabelRemoval() = descriptor.controlFlow.outputValues
        .flatMapTo(arrayListOf()) { it.originalExpressions.filterIsInstance<KtReturnExpression>() }

    fun createDeclaration(): KtNamedDeclaration {
        descriptor.controlFlow.jumpOutputValue?.elementsToReplace?.forEach { it.isJumpElementToReplace = true }
        getReturnsForLabelRemoval().forEach { it.isReturnForLabelRemoval = true }

        return with(descriptor.extractionData) {
            if (generatorOptions.inTempFile) {
                createTemporaryDeclaration("${getDeclarationPattern()}\n")
            } else {
                psiFactory.createDeclarationByPattern(
                    getDeclarationPattern(),
                    PsiChildRange(originalElements.firstOrNull(), originalElements.lastOrNull())
                )
            }
        }
    }

    fun getReturnArguments(resultExpression: KtExpression?): List<KtExpression> {
        return descriptor.controlFlow.outputValues
            .mapNotNull {
                when (it) {
                    is ExpressionValue -> resultExpression
                    is Jump -> if (it.conditional) psiFactory.createExpression("false") else null
                    is ParameterUpdate -> psiFactory.createExpression(it.parameter.nameForRef)
                    is Initializer -> psiFactory.createExpression(it.initializedDeclaration.name!!)
                    else -> throw IllegalArgumentException("Unknown output value: $it")
                }
            }
    }

    fun KtExpression.replaceWithReturn(replacingExpression: KtReturnExpression) {
        descriptor.controlFlow.defaultOutputValue?.let {
            val boxedExpression = replaced(replacingExpression).returnedExpression!!
            descriptor.controlFlow.outputValueBoxer.extractExpressionByValue(boxedExpression, it)
        }
    }

    fun getPublicApiInspectionIfEnabled(): PublicApiImplicitTypeInspection? {
        val project = descriptor.extractionData.project
        val inspectionProfileManager = ProjectInspectionProfileManager.getInstance(project)
        val inspectionProfile = inspectionProfileManager.currentProfile
        val state = inspectionProfile.getToolsOrNull("PublicApiImplicitType", project)?.defaultState ?: return null
        if (!state.isEnabled || state.level == HighlightDisplayLevel.DO_NOT_SHOW) return null
        return state.tool.tool as? PublicApiImplicitTypeInspection
    }

    fun useExplicitReturnType(): Boolean {
        if (descriptor.returnType.isFlexible()) return true
        val inspection = getPublicApiInspectionIfEnabled() ?: return false
        val targetClass = (descriptor.extractionData.targetSibling.parent as? KtClassBody)?.parent as? KtClassOrObject
        if ((targetClass != null && targetClass.isLocal) || descriptor.extractionData.isLocal()) return false
        val visibility = (descriptor.visibility ?: KtTokens.DEFAULT_VISIBILITY_KEYWORD).toVisibility()
        return when {
            visibility.isPublicAPI -> true
            inspection.reportInternal && visibility == DescriptorVisibilities.INTERNAL -> true
            inspection.reportPrivate && visibility == DescriptorVisibilities.PRIVATE -> true
            else -> false
        }
    }

    fun adjustDeclarationBody(declaration: KtNamedDeclaration) {
        val body = declaration.getGeneratedBody()

        (body.blockExpressionsOrSingle().singleOrNull() as? KtExpression)?.let {
            if (it.mustBeParenthesizedInInitializerPosition()) {
                it.replace(psiFactory.createExpressionByPattern("($0)", it))
            }
        }

        val jumpValue = descriptor.controlFlow.jumpOutputValue
        if (jumpValue != null) {
            val replacingReturn = psiFactory.createExpression(if (jumpValue.conditional) "return true" else "return")
            body.collectDescendantsOfType<KtExpression> { it.isJumpElementToReplace }.forEach {
                it.replace(replacingReturn)
                it.isJumpElementToReplace = false
            }
        }

        body.collectDescendantsOfType<KtReturnExpression> { it.isReturnForLabelRemoval }.forEach {
            it.getTargetLabel()?.delete()
            it.isReturnForLabelRemoval = false
        }

        /*
         * Sort by descending position so that internals of value/type arguments in calls and qualified types are replaced
         * before calls/types themselves
         */
        val currentRefs = body
            .collectDescendantsOfType<KtSimpleNameExpression> { it.resolveResult != null }
            .sortedByDescending { it.startOffset }

        currentRefs.forEach {
            val resolveResult = it.resolveResult!!
            val currentRef = if (it.isValid) {
                it
            } else {
                body.findDescendantOfType { expr -> expr.resolveResult == resolveResult } ?: return@forEach
            }
            val originalRef = resolveResult.originalRefExpr
            val newRef = descriptor.replacementMap[originalRef]
                .fold(currentRef as KtElement) { ref, replacement -> replacement(descriptor, ref) }
            (newRef as? KtSimpleNameExpression)?.resolveResult = resolveResult
        }

        if (generatorOptions.target == ExtractionTarget.PROPERTY_WITH_INITIALIZER) return

        if (body !is KtBlockExpression) throw AssertionError("Block body expected: ${descriptor.extractionData.codeFragmentText}")

        val firstExpression = body.statements.firstOrNull()
        if (firstExpression != null) {
            for (param in descriptor.parameters) {
                param.mirrorVarName?.let { varName ->
                    body.addBefore(psiFactory.createProperty(varName, null, true, param.name), firstExpression)
                    body.addBefore(psiFactory.createNewLine(), firstExpression)
                }
            }
        }

        val defaultValue = descriptor.controlFlow.defaultOutputValue

        val lastExpression = body.statements.lastOrNull()
        if (lastExpression is KtReturnExpression) return

        val defaultExpression =
            if (!generatorOptions.inTempFile && defaultValue != null && descriptor.controlFlow.outputValueBoxer
                    .boxingRequired && lastExpression!!.isMultiLine()
            ) {
                val varNameValidator = Fe10KotlinNewDeclarationNameValidator(body, lastExpression, KotlinNameSuggestionProvider.ValidatorTarget.VARIABLE)
                val resultVal = Fe10KotlinNameSuggester.suggestNamesByType(defaultValue.valueType, varNameValidator, null).first()
                body.addBefore(psiFactory.createDeclaration("val $resultVal = ${lastExpression.text}"), lastExpression)
                body.addBefore(psiFactory.createNewLine(), lastExpression)
                psiFactory.createExpression(resultVal)
            } else lastExpression

        val returnExpression =
            descriptor.controlFlow.outputValueBoxer.getReturnExpression(getReturnArguments(defaultExpression), psiFactory) ?: return

        when (generatorOptions.target) {
            ExtractionTarget.LAZY_PROPERTY, ExtractionTarget.FAKE_LAMBDALIKE_FUNCTION -> {
                // In the case of lazy property absence of default value means that output values are of OutputValue.Initializer type
                // We just add resulting expressions without return, since returns are prohibited in the body of lazy property
                if (defaultValue == null) {
                    body.appendElement(returnExpression.returnedExpression!!)
                }
                return
            }
            else -> {}
        }

        when {
            defaultValue == null -> body.appendElement(returnExpression)
            !defaultValue.callSiteReturn -> lastExpression!!.replaceWithReturn(returnExpression)
        }

        if (generatorOptions.allowExpressionBody) {
            val bodyExpression = body.statements.singleOrNull()
            val bodyOwner = body.parent as KtDeclarationWithBody
            val useExpressionBodyInspection = UseExpressionBodyInspection()
            if (bodyExpression != null && useExpressionBodyInspection.isActiveFor(bodyOwner)) {
                useExpressionBodyInspection.simplify(bodyOwner, !useExplicitReturnType())
            }
        }
    }

    fun insertDeclaration(declaration: KtNamedDeclaration, anchor: PsiElement): KtNamedDeclaration {
        declarationToReplace?.let { return it.replace(declaration) as KtNamedDeclaration }

        return with(descriptor.extractionData) {
            val targetContainer = anchor.parent!!
            // TODO: Get rid of explicit new-lines in favor of formatter rules
            val emptyLines = psiFactory.createWhiteSpace("\n\n")
            if (insertBefore) {
                (targetContainer.addBefore(declaration, anchor) as KtNamedDeclaration).apply {
                    targetContainer.addBefore(emptyLines, anchor)
                }
            } else {
                (targetContainer.addAfter(declaration, anchor) as KtNamedDeclaration).apply {
                    if (!(targetContainer is KtClassBody && (targetContainer.parent as? KtClass)?.isEnum() == true)) {
                        targetContainer.addAfter(emptyLines, anchor)
                    }
                }
            }
        }
    }

    val duplicates = if (generatorOptions.inTempFile) Collections.emptyList() else descriptor.duplicates

    val anchor = with(descriptor.extractionData) {
        val targetParent = targetSibling.parent

        val anchorCandidates = duplicates.mapTo(arrayListOf()) { it.range.elements.first().substringContextOrThis }
        anchorCandidates.add(targetSibling)
        if (targetSibling is KtEnumEntry) {
            anchorCandidates.add(targetSibling.siblings().last { it is KtEnumEntry })
        }

        val marginalCandidate = if (insertBefore) {
            anchorCandidates.minByOrNull { it.startOffset }!!
        } else {
            anchorCandidates.maxByOrNull { it.startOffset }!!
        }

        // Ascend to the level of targetSibling
        marginalCandidate.parentsWithSelf.first { it.parent == targetParent }
    }

    val shouldInsert = !(generatorOptions.inTempFile || generatorOptions.target == ExtractionTarget.FAKE_LAMBDALIKE_FUNCTION)
    val declaration = createDeclaration().let { if (shouldInsert) insertDeclaration(it, anchor) else it }
    adjustDeclarationBody(declaration)

    if (generatorOptions.inTempFile) return ExtractionResult(this, declaration, Collections.emptyMap())

    val replaceInitialOccurrence = {
        val arguments = descriptor.parameters.map { it.argumentText }
        makeCall(descriptor, declaration, descriptor.controlFlow, descriptor.extractionData.originalRange, arguments)
    }

    if (!generatorOptions.delayInitialOccurrenceReplacement) replaceInitialOccurrence()

    if (shouldInsert) {
        ShortenReferences.DEFAULT.process(declaration)
    }

    val duplicateReplacers = HashMap<KotlinPsiRange, () -> Unit>().apply {
        if (generatorOptions.delayInitialOccurrenceReplacement) {
            put(descriptor.extractionData.originalRange, replaceInitialOccurrence)
        }
        putAll(duplicates.map {
            val smartListRange = KotlinPsiRange.SmartListRange(it.range.elements)
            smartListRange to { makeCall(descriptor, declaration, it.controlFlow, smartListRange, it.arguments) }
        })
    }

    if (descriptor.typeParameters.isNotEmpty()) {
        for (ref in ReferencesSearch.search(declaration, LocalSearchScope(descriptor.getOccurrenceContainer()!!))) {
            val typeArgumentList = (ref.element.parent as? KtCallExpression)?.typeArgumentList ?: continue
            if (RemoveExplicitTypeArgumentsIntention.isApplicableTo(typeArgumentList, false)) {
                typeArgumentList.delete()
            }
        }
    }

    if (declaration is KtProperty) {
        if (declaration.isExtensionDeclaration() && !declaration.isTopLevel) {
            val receiverTypeReference = (declaration as? KtCallableDeclaration)?.receiverTypeReference
            receiverTypeReference?.siblings(withItself = false)?.firstOrNull { it.node.elementType == KtTokens.DOT }?.delete()
            receiverTypeReference?.delete()
        }
        if ((declaration.descriptor as? PropertyDescriptor)?.let { DescriptorUtils.isOverride(it) } == true) {
            val scope = declaration.getResolutionScope()
            val newName = Fe10KotlinNameSuggester.suggestNameByName(descriptor.name) {
                it != descriptor.name && scope.getAllAccessibleVariables(Name.identifier(it)).isEmpty()
            }
            declaration.setName(newName)
        }
    }

    CodeStyleManager.getInstance(descriptor.extractionData.project).reformat(declaration)

    return ExtractionResult(this, declaration, duplicateReplacers)
}
