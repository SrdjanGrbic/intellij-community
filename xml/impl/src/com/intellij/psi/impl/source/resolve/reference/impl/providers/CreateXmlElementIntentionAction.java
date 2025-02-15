/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package com.intellij.psi.impl.source.resolve.reference.impl.providers;

import com.intellij.codeInsight.intention.FileModifier;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.template.Template;
import com.intellij.codeInsight.template.TemplateManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.IncorrectOperationException;
import com.intellij.xml.XmlBundle;
import com.intellij.xml.analysis.XmlAnalysisBundle;
import com.intellij.xml.impl.schema.XsdNsDescriptor;
import com.intellij.xml.util.XmlUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.PropertyKey;

class CreateXmlElementIntentionAction implements IntentionAction {
  private final String myMessageKey;
  protected final TypeOrElementOrAttributeReference myRef;
  private boolean myIsAvailableEvaluated;
  private XmlFile myTargetFile;
  private final String myDeclarationTagName;

  CreateXmlElementIntentionAction(
    @PropertyKey(resourceBundle = XmlAnalysisBundle.BUNDLE) String messageKey,
    @NonNls @NotNull String declarationTagName,
    TypeOrElementOrAttributeReference ref) {

    myMessageKey = messageKey;
    myRef = ref;
    myDeclarationTagName = declarationTagName;
  }

  @Override
  @NotNull
  public String getText() {
    return XmlAnalysisBundle.message(myMessageKey, XmlUtil.findLocalNameByQualifiedName(myRef.getCanonicalText()));
  }

  @Override
  @NotNull
  public String getFamilyName() {
    return XmlBundle.message("xml.intention.create.xml.declaration");
  }

  @Override
  public boolean isAvailable(@NotNull final Project project, final Editor editor, final PsiFile file) {
    if (!myIsAvailableEvaluated) {
      final XmlTag tag = PsiTreeUtil.getParentOfType(myRef.getElement(), XmlTag.class);
      if (tag != null) {
        final XsdNsDescriptor descriptor = myRef.getDescriptor(tag, myRef.getCanonicalText(), new boolean[1]);

        if (descriptor != null &&
            descriptor.getDescriptorFile() != null &&
            descriptor.getDescriptorFile().isWritable()
           ) {
          myTargetFile = descriptor.getDescriptorFile();
        }
      }
      myIsAvailableEvaluated = true;
    }
    return myTargetFile != null;
  }

  @Override
  public void invoke(@NotNull final Project project, final Editor editor, final PsiFile file) throws IncorrectOperationException {
    final XmlTag rootTag = myTargetFile.getDocument().getRootTag();

    Editor targetEditor;
    if (myTargetFile.getVirtualFile() == null) {
      targetEditor = editor;
      targetEditor.getDocument().setText("");
      targetEditor.getCaretModel().moveToOffset(0);
    }
    else {
      targetEditor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(
        project,
        myTargetFile.getVirtualFile(),
        rootTag.getValue().getTextRange().getEndOffset()
      ), true);
    }
    TemplateManager manager = TemplateManager.getInstance(project);
    final Template template = manager.createTemplate("", "");

    addTextTo(template, rootTag);

    manager.startTemplate(targetEditor, template);
  }

  protected void addTextTo(Template template, XmlTag rootTag) {
    String schemaPrefix = rootTag.getPrefixByNamespace(XmlUtil.XML_SCHEMA_URI);
    if (!schemaPrefix.isEmpty()) schemaPrefix += ":";

    template.addTextSegment(
      "<" + schemaPrefix + myDeclarationTagName + " name=\"" + XmlUtil.findLocalNameByQualifiedName(myRef.getCanonicalText()) + "\">"
    );
    template.addEndVariable();
    template.addTextSegment(
      "</" + schemaPrefix + myDeclarationTagName + ">\n"
    );
    template.setToReformat(true);
  }

  @Override
  public boolean startInWriteAction() {
    return true;
  }

  @Override
  public @Nullable FileModifier getFileModifierForPreview(@NotNull PsiFile target) {
    PsiElement copy = PsiTreeUtil.findSameElementInCopy(myRef.getElement(), target);
    PsiReference reference = copy.getReference();

    CreateXmlElementIntentionAction intentionAction =
      new CreateXmlElementIntentionAction(myMessageKey, myDeclarationTagName, (TypeOrElementOrAttributeReference)reference);
    intentionAction.myTargetFile = (XmlFile)target;
    return intentionAction;
  }
}
