/*
 * Copyright 2020 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.android.projectsystem;

import com.android.tools.idea.projectsystem.ClassFileFinderUtil;
import com.android.tools.pixelprobe.util.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.idea.blaze.android.sync.model.AndroidResourceModule;
import com.google.idea.blaze.android.sync.model.AndroidResourceModuleRegistry;
import com.google.idea.blaze.android.targetmaps.ReverseBinaryDependencyMap;
import com.google.idea.blaze.base.command.buildresult.OutputArtifactResolver;
import com.google.idea.blaze.base.ideinfo.AndroidIdeInfo;
import com.google.idea.blaze.base.ideinfo.ArtifactLocation;
import com.google.idea.blaze.base.ideinfo.TargetIdeInfo;
import com.google.idea.blaze.base.ideinfo.TargetKey;
import com.google.idea.blaze.base.io.VirtualFileSystemProvider;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.sync.data.BlazeProjectDataManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.ex.temp.TempFileSystem;
import java.io.File;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link BlazeClassFileFinder} that uses deploy JARs for class files. This only works for
 * resource modules (i.e. not the .workspace module).
 *
 * <p>The Blaze targets that go into creating a resource module is known. Consequently, it is
 * possible to determine which binaries in the projectview depends on the resource blaze targets.
 * This class calculates the binary targets and attempts to find classes from deploy JARs of the
 * binary targets.
 *
 * <p>NOTE: Blaze targets that constitutes the resource module will be called "resource target(s)"
 * in comments below.
 */
public class DeployJarClassFileFinder implements BlazeClassFileFinder {
  static final String CLASS_FINDER_KEY = "DeployJarClassFileFinder";

  private static final Logger LOG = Logger.getInstance(DeployJarClassFileFinder.class);

  private final Module module;
  private final Project project;

  // Finder to fall back to if we can't find any binaries in the project, or if none of the binaries
  // can resolve the class
  private final PsiBasedClassFileFinder fallbackFinder;

  // the binary targets that depend on resource targets. If a resource target is a binary, it will
  // be included in this list as well
  private ImmutableList<TargetKey> binaryTargets = ImmutableList.of();

  // field to track which binary target to start looking from
  private int currentBinary = 0;

  // lastSyncCount is used to determine whether we need to recalculate binaryTargets
  private int lastSyncCount = -1;

  public DeployJarClassFileFinder(Module module) {
    this.module = module;
    this.project = module.getProject();
    fallbackFinder = new PsiBasedClassFileFinder(module);
  }

  @Override
  public boolean shouldSkipResourceRegistration() {
    return false;
  }

  @Nullable
  @Override
  public VirtualFile findClassFile(String fqcn) {
    updateBinaryTargetsIfNecessary();

    BlazeProjectData projectData =
        BlazeProjectDataManager.getInstance(project).getBlazeProjectData();
    if (projectData == null) {
      return fallbackFinder.findClassFile(fqcn);
    }

    if (currentBinary == -1) {
      return fallbackFinder.findClassFile(fqcn);
    }

    // Starting from `currentJar` look at all binaries until we find the class file.
    // We want to look at binaries that we had looked at before because it might be a new render
    // source
    for (int i = 0; i < binaryTargets.size(); i++) {
      VirtualFile virtualFile = getClassFromActiveDeployJar(projectData, fqcn);
      if (virtualFile != null) {
        return virtualFile;
      }
      currentBinary = (currentBinary + 1) % binaryTargets.size();
    }

    if (binaryTargets.isEmpty()) {
      LOG.warn("No binaries for module " + module.getName());
    } else {
      LOG.warn(
          String.format(
              "Could not find %s\nModule: %s\nBinary Targets:\n  %s",
              fqcn,
              module.getName(),
              Strings.join(
                  binaryTargets.stream().map(TargetKey::getLabel).collect(Collectors.toList()),
                  "\n  ")));
    }

    return fallbackFinder.findClassFile(fqcn);
  }

  /**
   * Returns class file for fqcn if found in the deploy JAR corresponding to {@link #currentBinary}.
   * Returns null if something goes wrong or if deploy JAR does not contain fqcn
   */
  @Nullable
  private VirtualFile getClassFromActiveDeployJar(BlazeProjectData projectData, String fqcn) {
    if (currentBinary >= binaryTargets.size()) {
      return null;
    }

    TargetKey activeBinaryTarget = binaryTargets.get(currentBinary);
    TargetIdeInfo ideInfo = projectData.getTargetMap().get(activeBinaryTarget);
    if (ideInfo == null) {
      return null;
    }

    AndroidIdeInfo androidIdeInfo = ideInfo.getAndroidIdeInfo();
    if (androidIdeInfo == null) {
      return null;
    }

    ArtifactLocation deployJar = androidIdeInfo.getDeployJar();
    if (deployJar == null) {
      return null;
    }

    File deployJarFile =
        OutputArtifactResolver.resolve(
            project, projectData.getArtifactLocationDecoder(), deployJar);
    if (deployJarFile == null) {
      LOG.warn("Could not find deploy jar for " + activeBinaryTarget.getLabel());
      return null;
    }

    VirtualFile deployJarVF =
        VirtualFileSystemProvider.getInstance().getSystem().findFileByIoFile(deployJarFile);
    if (deployJarVF == null) {
      return null;
    }

    VirtualFile classFile = findClassInJar(deployJarVF, fqcn);
    // #as41: This call can be removed once as4.1 is paved
    ResourceRepositoryUpdater.registerResourcePackageForClass(module, classFile, fqcn);
    return classFile;
  }

  /**
   * Recalculates {@link #binaryTargets} if {@link #lastSyncCount} is behind the latest sync count
   * from {@link SyncCounter}
   */
  private void updateBinaryTargetsIfNecessary() {
    int latestSyncCount = SyncCounter.getInstance(project).getSyncCount();
    if (lastSyncCount != latestSyncCount) {
      this.binaryTargets = collectDependentBinaryTargets();
      // Reset current JAR pointer if binary targets are recalculated since ordering may not remain
      // the same
      this.currentBinary = 0;
      this.lastSyncCount = latestSyncCount;
    }
  }

  /**
   * For the given module, calculates the binaries in projectview that depend on resource targets
   *
   * <p>NOTE: One binary target might depend on a subset of the resource targets, so we track
   * multiple binaries per module.
   */
  private ImmutableList<TargetKey> collectDependentBinaryTargets() {
    BlazeProjectData blazeProjectData =
        BlazeProjectDataManager.getInstance(project).getBlazeProjectData();
    if (blazeProjectData == null) {
      return ImmutableList.of();
    }

    AndroidResourceModule androidResourceModule =
        AndroidResourceModuleRegistry.getInstance(project).get(module);

    ImmutableMultimap<TargetKey, TargetKey> rDepsMap = ReverseBinaryDependencyMap.get(project);
    return androidResourceModule.sourceTargetKeys.stream()
        .flatMap(k -> rDepsMap.get(k).stream())
        .distinct()
        .collect(ImmutableList.toImmutableList());
  }

  /** Opens a fiven jar to look for class corresponding to fqcn */
  @Nullable
  private static VirtualFile findClassInJar(final VirtualFile classJar, String fqcn) {
    VirtualFile jarRoot = getJarRootForLocalFile(classJar);
    if (jarRoot == null) {
      return null;
    }
    return ClassFileFinderUtil.findClassFileInOutputRoot(jarRoot, fqcn);
  }

  /** Test aware method to redirect JARs {@link TempFileSystem} for unit tests */
  private static VirtualFile getJarRootForLocalFile(VirtualFile file) {
    return ApplicationManager.getApplication().isUnitTestMode()
        ? TempFileSystem.getInstance().findFileByPath(file.getPath() + JarFileSystem.JAR_SEPARATOR)
        : JarFileSystem.getInstance().getJarRootForLocalFile(file);
  }

  public static boolean isEnabled() {
    return BlazeClassFileFinderFactory.getClassFileFinderName().equals(CLASS_FINDER_KEY);
  }
}
