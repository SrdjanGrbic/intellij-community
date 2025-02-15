// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.refactoring.rename;

import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.psi.PsiElement;
import com.intellij.refactoring.rename.RenameProcessor;
import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCase;
import org.jetbrains.kotlin.test.TestMetadata;
import org.jetbrains.kotlin.idea.test.TestRoot;

@TestRoot("idea/tests")
@TestMetadata("testData/refactoring/rename/simpleNameReference")
public class SimpleNameReferenceRenameTest extends KotlinLightCodeInsightFixtureTestCase {
    public void testRenameLabel() throws Exception {
        doTest("foo");
    }

    public void testRenameLabel2() throws Exception {
        doTest("anotherFoo");
    }

    public void testRenameField() throws Exception {
        doTest("renamed");
    }

    public void testRenameFieldIdentifier() throws Exception {
        doTest("anotherRenamed");
    }

    public void testMemberOfLocalObject() throws Exception {
        doTest("bar");
    }

    public void testLocalFunction() throws Exception {
        doTest("xyzzy");
    }

    public void testParameterOfCopyMethod() throws Exception {
        doTest("y");
    }

    private void doTest(String newName) throws Exception {
        myFixture.configureByFile(getTestName(true) + ".kt");
        PsiElement element = TargetElementUtil
                .findTargetElement(getEditor(),
                                   TargetElementUtil.ELEMENT_NAME_ACCEPTED | TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED);
        assertNotNull(element);
        new RenameProcessor(getProject(), element, newName, true, true).run();
        myFixture.checkResultByFile(getTestName(true) + ".kt.after");
    }
}
