// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.application;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Provides an ability to customize the paths where configuration and caches of IDE will be stored.
 * The name of the implementing class should be passed to the JVM command line via 'idea.paths.customizer' system property.
 */
@ApiStatus.Internal
public interface PathCustomizer {
  @Nullable CustomPaths customizePaths();

  class CustomPaths {
    public CustomPaths(@Nullable String configPath, @Nullable String systemPath, @Nullable String pluginsPath) {
      this.configPath = configPath;
      this.systemPath = systemPath;
      this.pluginsPath = pluginsPath;
    }

    public final @Nullable String configPath;
    public final @Nullable String systemPath;
    public final @Nullable String pluginsPath;
  }
}
