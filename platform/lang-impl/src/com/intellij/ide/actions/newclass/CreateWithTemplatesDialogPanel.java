// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.actions.newclass;

import com.intellij.ide.ui.newItemPopup.NewItemWithTemplatesPopupPanel;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.util.Trinity;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.ExperimentalUI;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.fields.ExtendableTextComponent;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.accessibility.AccessibleContext;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

public class CreateWithTemplatesDialogPanel extends NewItemWithTemplatesPopupPanel<Trinity<String, Icon, String>> {

  public CreateWithTemplatesDialogPanel(@NotNull List<Trinity<String, Icon, String>> templates, @Nullable String selectedItem) {
    super(templates, new TemplateListCellRenderer());
    myTemplatesList.addListSelectionListener(e -> {
      Trinity<String, Icon, String> selectedValue = myTemplatesList.getSelectedValue();
      if (selectedValue != null) {
        setTextFieldIcon(selectedValue.second);
      }
    });
    if (ExperimentalUI.isNewUI()) {
      myTemplatesList.setBackground(JBUI.CurrentTheme.Popup.BACKGROUND);
    }
    selectTemplate(selectedItem);
    setTemplatesListVisible(templates.size() > 1);
  }

  public JTextField getNameField() {
    return myTextField;
  }

  @NotNull
  public String getEnteredName() {
    return myTextField.getText().trim();
  }

  @NotNull
  public String getSelectedTemplate() {
    return myTemplatesList.getSelectedValue().third;
  }

  private void setTextFieldIcon(Icon icon) {
    myTextField.setExtensions(new TemplateIconExtension(icon));
    myTextField.repaint();
  }

  private void selectTemplate(@Nullable String selectedItem) {
    ListModel<Trinity<String, Icon, String>> model = myTemplatesList.getModel();
    for (int i = 0; i < model.getSize(); i++) {
      String templateID = model.getElementAt(i).getThird();
      if (StringUtil.equals(selectedItem, templateID)) {
        myTemplatesList.setSelectedIndex(i);
        return;
      }
    }

    myTemplatesList.setSelectedIndex(0);
  }

  private static class TemplateListCellRenderer implements ListCellRenderer<Trinity<String, Icon, String>> {
    private final ListCellRenderer<Trinity<String, Icon, String>> delegateRenderer =
      SimpleListCellRenderer.create((@NotNull JBLabel label, Trinity<@NlsContexts.ListItem String, Icon, String> value, int index) -> {
        if (value != null) {
          label.setText(value.first);
          label.setIcon(value.second);
        }
      });

    @Override
    public Component getListCellRendererComponent(JList<? extends Trinity<String, Icon, String>> list,
                                                  Trinity<String, Icon, String> value,
                                                  int index,
                                                  boolean isSelected,
                                                  boolean cellHasFocus) {
      JComponent delegate = (JComponent) delegateRenderer.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
      delegate.setBorder(JBUI.Borders.empty(JBUIScale.scale(3), JBUIScale.scale(1)));
      if (index == 0 && ExperimentalUI.isNewUI()) {
        JPanel wrapper = new JPanel(new BorderLayout()) {
          @Override
          public AccessibleContext getAccessibleContext() {
            return delegate.getAccessibleContext();
          }
        };
        wrapper.setBackground(JBUI.CurrentTheme.Popup.BACKGROUND);
        //noinspection UseDPIAwareBorders
        wrapper.setBorder(new EmptyBorder(JBUI.CurrentTheme.NewClassDialog.fieldsSeparatorWidth(), 0, 0, 0));
        wrapper.add(delegate, BorderLayout.CENTER);
        return wrapper;
      }
      return delegate;
    }
  }

  private static final class TemplateIconExtension implements ExtendableTextComponent.Extension {
    private final Icon icon;

    private TemplateIconExtension(Icon icon) {this.icon = icon;}

    @Override
    public Icon getIcon(boolean hovered) {
      return icon;
    }

    @Override
    public boolean isIconBeforeText() {
      return true;
    }
  }

}
