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

import com.google.idea.blaze.base.scope.output.PrintOutput;
import com.google.idea.blaze.base.scope.output.StatusOutput;
import com.google.idea.common.experiments.BoolExperiment;
import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.HyperlinkInfo;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/** Service that controls the view of the Blaze Outputs Tool Window. (Controller part of MVC.) */
public final class TasksToolWindowController {

  public static final BoolExperiment enabled = new BoolExperiment("blazeconsole.v2", false);

  private final Model model = new Model();
  private final View view;

  public TasksToolWindowController(Project project) {
    this.view = new NoOpView(); // TODO(olegsa) replace with actual view when it is submitted
  }

  /** Mark the given task as started and notify the view to reflect the started task. */
  public void startTask(Task task, List<Filter> consoleFilters) {
    model.startTask(task);
    view.taskStarted(task, consoleFilters);
  }

  /** Update the state and view with new task output */
  public void output(Task task, PrintOutput output) {
    view.taskOutput(task, output);
  }

  /** Update the state and the view with new task status */
  public void status(Task task, StatusOutput output) {
    view.statusOutput(task, output);
  }

  /** Update the state and the view when task finishes */
  public void finishTask(Task task) {
    model.stopTask(task);
    view.taskFinished(task);
  }

  /** Move task to a new parent task */
  public void moveTask(Task task, @Nullable Task newParent) {
    model.changeParent(task, newParent);
    view.moveTask(task, newParent);
  }

  /** Open given task's output hyperlink */
  public void navigate(Task task, HyperlinkInfo link, int offset) {
    view.navigate(task, link, offset);
  }

  /** Activate the view */
  public void activate() {
    view.activate();
  }

  public static TasksToolWindowController getInstance(Project project) {
    return ServiceManager.getService(project, TasksToolWindowController.class);
  }

  /** State of all active and finished tasks. (Model part of MVC.) */
  private static final class Model {
    private final Set<Task> activeTasks = ConcurrentHashMap.newKeySet();
    private final Set<Task> finishedTasks = ConcurrentHashMap.newKeySet();

    void startTask(Task task) {
      activeTasks.add(task);
      task.start();
    }

    void stopTask(Task task) {
      activeTasks.remove(task);
      finishedTasks.add(task);
      task.finish();
    }

    void changeParent(Task task, @Nullable Task newParent) {
      task.setParent(newParent);
    }
  }

  // TODO(olegsa): delete after the real view is submitted
  /** The View that doesn't do anything. */
  private static final class NoOpView implements View {

    @Override
    public void taskStarted(Task task, List<Filter> consoleFilters) {}

    @Override
    public void taskOutput(Task task, PrintOutput output) {}

    @Override
    public void statusOutput(Task task, StatusOutput output) {}

    @Override
    public void taskFinished(Task task) {}

    @Override
    public void moveTask(Task task, @Nullable Task newParent) {}

    @Override
    public void navigate(Task task, HyperlinkInfo link, int offset) {}

    @Override
    public void activate() {}
  }
}
