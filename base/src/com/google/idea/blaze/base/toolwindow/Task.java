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
package com.google.idea.blaze.base.toolwindow;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import javax.annotation.Nullable;

/** Represents a Blaze Outputs Tool Window Task, which can be hierarchical. */
public final class Task {
  final String name;
  final Type type;
  @Nullable private Task parent;
  private String status = "";
  @Nullable private Instant startTime;
  @Nullable private Instant endTime;

  public Task(String name, Type type) {
    this(name, type, null);
  }

  public Task(String name, Type type, @Nullable Task parent) {
    this.name = name;
    this.type = type;
    this.parent = parent;
  }

  void start() {
    startTime = Instant.now();
  }

  void finish() {
    endTime = Instant.now();
  }

  boolean isFinished() {
    return endTime != null;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  @Nullable
  public Task getParent() {
    return parent;
  }

  public void setParent(@Nullable Task parent) {
    this.parent = parent;
  }

  @Nullable
  public Instant getStartTime() {
    return startTime;
  }

  @Nullable
  public Instant getEndTime() {
    return endTime;
  }

  @Override
  public String toString() {
    if (startTime == null) {
      return name + " (not started)";
    }
    ZonedDateTime time = startTime.atZone(ZoneId.systemDefault());
    return name + ' ' + time.getHour() + ':' + time.getMinute() + ':' + time.getSecond();
  }

  /** Type of the task. */
  public enum Type {
    // TODO(olegsa) consider merging some categories
    G4_FIX("G4 Fix"),
    G4_LINT("G4 Lint"),
    BUILD_CLEANER("Build Cleaner"),
    FIX_DEPS("Fix Deps"),
    SUGGESTED_FIXES("Suggested Fixes"),
    FAST_BUILD("Fast Build"),
    DEPLOYABLE_JAR("DeployableJar"),
    BLAZE_MAKE("Blaze Make"),
    BLAZE_BEFORE_RUN("Blaze Before Run"),
    BLAZE_SYNC("Blaze Sync");

    private final String displayName;

    Type(String displayName) {
      this.displayName = displayName;
    }

    @Override
    public String toString() {
      return displayName;
    }
  }
}
