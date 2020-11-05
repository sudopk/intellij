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
package com.android.tools.idea.rendering;

import static org.junit.Assert.fail;

import com.android.sdklib.devices.Device;
import com.android.tools.idea.configurations.Configuration;
import com.android.tools.idea.configurations.ConfigurationManager;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ui.UIUtil;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.jetbrains.annotations.NotNull;

/** Utils for ASwB's render tests. */
public class AswbRenderTestUtils {
  public static final String DEFAULT_DEVICE_ID = "Nexus 4";
  public static final String LAYOUTLIB_PATH = "plugins/android/lib/layoutlib/";

  private static String getStudioSdkPath() {
    ApplicationInfo appInfo = ApplicationInfo.getInstance();
    return String.format(
        "./third_party/java/jetbrains/plugin_api/android_studio_%s_%s/",
        appInfo.getMajorVersion(), appInfo.getMinorVersion());
  }

  /** Method to be called before every render test case. */
  public static void beforeRenderTestCase() throws IOException {
    // layoutlib resources are provided by the SDK. Studio only searches through a few pre-defined
    // locations. Symlink the resources folder to a location studio will find.
    File srcFolder = new File(getStudioSdkPath(), LAYOUTLIB_PATH).getAbsoluteFile();
    File destFolder = new File(PathManager.getHomePath(), LAYOUTLIB_PATH);
    Files.createDirectories(destFolder.getParentFile().toPath());
    Files.createSymbolicLink(destFolder.toPath(), srcFolder.getAbsoluteFile().toPath());

    RenderService.shutdownRenderExecutor(5);
    RenderService.initializeRenderExecutor();
  }

  /** Method to be called before every after test case. */
  public static void afterRenderTestCase() throws IOException {
    // Delete the layoutlib resource folder manually linked in #beforeRenderTestCase if exists.
    Files.deleteIfExists(Paths.get(PathManager.getHomePath(), LAYOUTLIB_PATH));

    RenderLogger.resetFidelityErrorsFilters();
    waitForRenderTaskDisposeToFinish();
  }

  /** Waits for any RenderTask dispose threads to finish */
  public static void waitForRenderTaskDisposeToFinish() {
    // Make sure there is no RenderTask disposing event in the event queue.
    UIUtil.dispatchAllInvocationEvents();
    Thread.getAllStackTraces().keySet().stream()
        .filter(t -> t.getName().startsWith("RenderTask dispose"))
        .forEach(
            t -> {
              try {
                t.join(10 * 1000); // 10s
              } catch (InterruptedException e) {
                throw new RuntimeException(e);
              }
            });
  }

  @NotNull
  public static Configuration getConfiguration(@NotNull Module module, @NotNull VirtualFile file) {
    return getConfiguration(module, file, DEFAULT_DEVICE_ID);
  }

  @NotNull
  public static Configuration getConfiguration(
      @NotNull Module module, @NotNull VirtualFile file, @NotNull String deviceId) {
    ConfigurationManager configurationManager = ConfigurationManager.getOrCreateInstance(module);
    Configuration configuration = configurationManager.getConfiguration(file);
    configuration.setDevice(findDeviceById(configurationManager, deviceId), false);

    return configuration;
  }

  @NotNull
  private static Device findDeviceById(ConfigurationManager manager, String id) {
    for (Device device : manager.getDevices()) {
      if (device.getId().equals(id)) {
        return device;
      }
    }
    fail("Can't find device " + id);
    throw new IllegalStateException();
  }

  private AswbRenderTestUtils() {}
}
