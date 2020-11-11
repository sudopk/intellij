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
package com.google.idea.blaze.android.targetmaps;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.idea.blaze.android.sync.importer.BlazeImportUtil;
import com.google.idea.blaze.android.sync.model.AndroidResourceModule;
import com.google.idea.blaze.android.sync.model.BlazeAndroidSyncData;
import com.google.idea.blaze.base.ideinfo.TargetIdeInfo;
import com.google.idea.blaze.base.ideinfo.TargetKey;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.model.primitives.RuleType;
import com.google.idea.blaze.base.sync.SyncCache;
import com.google.idea.blaze.base.targetmaps.TransitiveDependencyMap;
import com.intellij.openapi.project.Project;
import java.util.stream.Stream;

/**
 * Maps targets that have resource modules in the project to the binaries that consume those
 * targets. The map is recalculated and cached for every sync.
 */
public class ReverseBinaryDependencyMap {
  private ReverseBinaryDependencyMap() {}

  public static ImmutableMultimap<TargetKey, TargetKey> get(Project project) {
    ImmutableMultimap<TargetKey, TargetKey> map =
        SyncCache.getInstance(project)
            .get(
                ReverseBinaryDependencyMap.class,
                ReverseBinaryDependencyMap::createReverseBinaryDepsMap);
    return map != null ? map : ImmutableMultimap.of();
  }

  public static ImmutableMultimap<TargetKey, TargetKey> createReverseBinaryDepsMap(
      Project project, BlazeProjectData projectData) {
    ImmutableMultimap.Builder<TargetKey, TargetKey> builder = ImmutableMultimap.builder();

    // Can't calculate map if android sync data is not available
    BlazeAndroidSyncData androidSyncData =
        projectData.getSyncState().get(BlazeAndroidSyncData.class);
    if (androidSyncData == null) {
      return builder.build();
    }

    // Gather all targets that ended up in a resource module
    ImmutableList<AndroidResourceModule> androidResourceModules =
        androidSyncData.importResult.androidResourceModules;
    ImmutableSet<TargetKey> resourceTargets =
        androidResourceModules.stream()
            .flatMap(m -> m.sourceTargetKeys.stream())
            .collect(ImmutableSet.toImmutableSet());

    // Get all android_binary source targets
    // Note: BlazeImportUtil.getSourceTargetsStream already filters non-android targets
    Stream<TargetIdeInfo> sourceTargetsStream = BlazeImportUtil.getSourceTargetsStream(project);

    ImmutableSet<TargetIdeInfo> sourceBinaries =
        sourceTargetsStream
            .filter(t -> RuleType.BINARY.equals(t.getKind().getRuleType()))
            .collect(ImmutableSet.toImmutableSet());

    // Ask TransitiveDependencyMap if the binary is dependant on any of resource targets
    // This operation will be expensive, but should only have to run once per sync
    TransitiveDependencyMap transitiveDepsMap = TransitiveDependencyMap.getInstance(project);
    for (TargetIdeInfo sourceBinary : sourceBinaries) {
      // Add to target map if a resource target is itself a binary
      if (resourceTargets.contains(sourceBinary.getKey())) {
        builder.put(sourceBinary.getKey(), sourceBinary.getKey());
      }

      transitiveDepsMap
          .filterPossibleTransitiveDeps(sourceBinary.getKey(), resourceTargets)
          .forEach(k -> builder.put(k, sourceBinary.getKey()));
    }

    return builder.build();
  }
}
