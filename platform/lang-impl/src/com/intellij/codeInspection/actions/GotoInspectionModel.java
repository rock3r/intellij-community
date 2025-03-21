// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInspection.actions;

import com.intellij.codeInspection.ex.InspectionProfileImpl;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper;
import com.intellij.codeInspection.ex.ScopeToolState;
import com.intellij.ide.IdeBundle;
import com.intellij.ide.util.gotoByName.SimpleChooseByNameModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.profile.codeInspection.InspectionProfileManager;
import com.intellij.psi.PsiManager;
import com.intellij.util.ArrayUtilRt;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Konstantin Bulenkov
 */
public final class GotoInspectionModel extends SimpleChooseByNameModel {
  private final Map<String, InspectionToolWrapper> myToolNames = new HashMap<>();
  private final String[] myNames;
  private final ListCellRenderer<?> myListCellRenderer = InspectionListUtilKt.cellRenderer();


  public GotoInspectionModel(@NotNull Project project) {
    super(project, IdeBundle.message("prompt.goto.inspection.enter.name"), "goto.inspection.help.id");

    InspectionProfileImpl rootProfile = InspectionProfileManager.getInstance(project).getCurrentProfile();
    for (ScopeToolState state : rootProfile.getAllTools()) {
      InspectionToolWrapper tool = LocalInspectionToolWrapper.findTool2RunInBatch(project, null, rootProfile, state.getTool());
      if (tool != null) {
        myToolNames.put(getSearchString(tool), tool);
      }
    }
    myNames = ArrayUtilRt.toStringArray(myToolNames.keySet());
  }

  private static String getSearchString(InspectionToolWrapper tool) {
    return tool.getDisplayName() + " " + StringUtil.join(tool.getGroupPath(), " ") + " " + tool.getShortName();
  }

  @Override
  public @NotNull ListCellRenderer<?> getListCellRenderer() {
    return myListCellRenderer;
  }

  @Override
  public String[] getNames() {
    return myNames;
  }

  @Override
  public Object[] getElementsByName(final String name, final String pattern) {
    final InspectionToolWrapper tool = myToolNames.get(name);
    if (tool == null) {
      return InspectionElement.EMPTY_ARRAY;
    }
    return new InspectionElement[] {new InspectionElement(tool, PsiManager.getInstance(getProject()))};
  }

  @Override
  public String getElementName(final @NotNull Object element) {
    if (element instanceof InspectionElement) {
      return getSearchString(((InspectionElement)element).getToolWrapper());
    }
    return null;
  }
}
