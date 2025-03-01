// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.kotlin.inspections

import com.intellij.testFramework.TestDataPath
import org.jetbrains.idea.devkit.kotlin.DevkitKtTestsUtil

@TestDataPath("/inspections/companionObjectInExtension")
class KtCompanionObjectInExtensionInspectionTest : KtCompanionObjectInExtensionInspectionTestBase() {

  override fun getBasePath() = DevkitKtTestsUtil.TESTDATA_PATH + "inspections/companionObjectInExtension"

  private fun doTestHighlighting() {
    myFixture.testHighlighting("${getTestName(false)}.$fileExtension")
  }

  fun testNoHighlighting() {
    doTestHighlighting()
  }

  fun testExtensionWithCompanionObject() {
    doTestHighlighting()
  }

  fun testExtensionWithLoggerAndConstVal() {
    doTestHighlighting()
  }
}
