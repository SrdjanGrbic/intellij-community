// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gradle.service.resolve.staticModel.impl.plugins

import org.gradle.util.GradleVersion
import org.jetbrains.plugins.gradle.service.resolve.staticModel.api.GradleStaticPluginDescriptor
import org.jetbrains.plugins.gradle.service.resolve.staticModel.api.GradleStaticPluginEntry
import org.jetbrains.plugins.gradle.service.resolve.staticModel.api.GradleStaticPluginNamespace

internal class ScalaPluginDescriptor : GradleStaticPluginDescriptor {
  override val pluginEntry: GradleStaticPluginEntry = GradleStaticPluginEntry.SCALA

  override val dependencies: List<GradleStaticPluginEntry> = listOf(
    GradleStaticPluginEntry.SCALA_BASE,
    GradleStaticPluginEntry.JAVA,
  )

  override fun GradleStaticPluginNamespace.configure(gradleVersion: GradleVersion) {
    task("scaladoc")
    task("compileScala")
  }
}