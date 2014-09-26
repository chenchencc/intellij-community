/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package com.intellij.openapi.components.impl.stores;

import com.intellij.openapi.components.StateStorage;
import com.intellij.openapi.components.StateStorageException;
import com.intellij.openapi.components.TrackingPathMacroSubstitutor;
import com.intellij.openapi.components.store.ComponentSaveSession;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.impl.ModuleImpl;
import com.intellij.openapi.project.impl.ProjectImpl;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.SmartHashSet;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * @author yole
 */
public class ProjectWithModulesStoreImpl extends ProjectStoreImpl {
  public ProjectWithModulesStoreImpl(@NotNull ProjectImpl project) {
    super(project);
  }

  @Override
  public void reinitComponents(@NotNull Set<String> componentNames, boolean reloadData) {
    super.reinitComponents(componentNames, reloadData);

    for (Module module : getPersistentModules()) {
      ((ModuleImpl)module).getStateStore().reinitComponents(componentNames, reloadData);
    }
  }

  @Override
  public TrackingPathMacroSubstitutor[] getSubstitutors() {
    List<TrackingPathMacroSubstitutor> result = new SmartList<TrackingPathMacroSubstitutor>();
    ContainerUtil.addIfNotNull(result, getStateStorageManager().getMacroSubstitutor());

    for (Module module : getPersistentModules()) {
      ContainerUtil.addIfNotNull(result, ((ModuleImpl)module).getStateStore().getStateStorageManager().getMacroSubstitutor());
    }

    return result.toArray(new TrackingPathMacroSubstitutor[result.size()]);
  }

  @Override
  public boolean isReloadPossible(@NotNull Set<String> componentNames) {
    if (!super.isReloadPossible(componentNames)) {
      return false;
    }

    for (Module module : getPersistentModules()) {
      if (!((ModuleImpl)module).getStateStore().isReloadPossible(componentNames)) {
        return false;
      }
    }

    return true;
  }

  @NotNull
  protected Module[] getPersistentModules() {
    ModuleManager moduleManager = ModuleManager.getInstance(myProject);
    return moduleManager == null ? Module.EMPTY_ARRAY : moduleManager.getModules();
  }

  @Override
  protected SaveSessionImpl createSaveSession() throws StateStorageException {
    return new ProjectWithModulesSaveSession();
  }

  private class ProjectWithModulesSaveSession extends ProjectSaveSession {
    List<ComponentSaveSession> myModuleSaveSessions = new SmartList<ComponentSaveSession>();

    public ProjectWithModulesSaveSession() {
      for (Module module : getPersistentModules()) {
        myModuleSaveSessions.add(((ModuleImpl)module).getStateStore().startSave());
      }
    }

    @NotNull
    @Override
    public List<File> getAllStorageFiles(boolean includingSubStructures) {
      List<File> result = super.getAllStorageFiles(includingSubStructures);
      if (includingSubStructures) {
        for (ComponentSaveSession moduleSaveSession : myModuleSaveSessions) {
          result.addAll(moduleSaveSession.getAllStorageFiles(true));
        }
      }
      return result;
    }

    @Override
    @Nullable
    public Set<String> analyzeExternalChanges(@NotNull Set<Pair<VirtualFile, StateStorage>> changedFiles) {
      Set<String> superResult = super.analyzeExternalChanges(changedFiles);
      if (superResult == null) {
        return null;
      }

      Set<String> result = superResult.isEmpty() ? null : new THashSet<String>(superResult);
      for (ComponentSaveSession moduleSaveSession : myModuleSaveSessions) {
        Set<String> s = moduleSaveSession.analyzeExternalChanges(changedFiles);
        if (s == null) {
          return null;
        }
        if (!s.isEmpty()) {
          if (result == null) {
            result = new SmartHashSet<String>();
          }
          result.addAll(s);
        }
      }
      return result == null ? Collections.<String>emptySet() : result;
    }

    @Override
    public void finishSave() {
      try {
        Throwable first = null;
        for (ComponentSaveSession moduleSaveSession : myModuleSaveSessions) {
          try {
            moduleSaveSession.finishSave();
          }
          catch (Throwable e) {
            if (first == null) {
              first = e;
            }
          }
        }

        if (first != null) {
          throw new RuntimeException(first);
        }
      }
      finally {
        super.finishSave();
      }
    }

    @Override
    public void reset() {
      try {
        for (ComponentSaveSession moduleSaveSession : myModuleSaveSessions) {
          moduleSaveSession.reset();
        }
      }
      finally {
        super.reset();
      }
    }

    @Override
    protected void beforeSave(@NotNull List<Pair<StateStorageManager.SaveSession, VirtualFile>> readonlyFiles) {
      super.beforeSave(readonlyFiles);

      for (ComponentSaveSession moduleSaveSession : myModuleSaveSessions) {
        moduleSaveSession.save(readonlyFiles);
      }
    }

    @Override
    protected void collectSubFilesToSave(@NotNull List<File> result) {
      for (ComponentSaveSession moduleSaveSession : myModuleSaveSessions) {
        result.addAll(moduleSaveSession.getAllStorageFilesToSave(true));
      }
    }
  }
}
