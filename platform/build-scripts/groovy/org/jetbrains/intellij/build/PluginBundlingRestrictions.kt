// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build

import kotlinx.collections.immutable.PersistentList

/**
 * Allows to exclude a plugin from some distributions.
 *
 * @see org.jetbrains.intellij.build.impl.PluginLayout.PluginLayoutSpec#getBundlingRestrictions()
 */
internal data class PluginBundlingRestrictions(
  /**
   * Change this value if the plugin works in some OS only and therefore don't need to be bundled with distributions for other OS.
   */
  @JvmField
  val supportedOs: PersistentList<OsFamily>,

  /**
   * Change this value if the plugin works on some architectures only and
   * therefore don't need to be bundled with distributions for other architectures.
   */
  @JvmField
  val supportedArch: List<JvmArchitecture>,

  /**
   * Set to {@code true} if the plugin should be included in distribution for EAP builds only.
   */
  @JvmField
  val includeInEapOnly: Boolean,
) {
  companion object {
    @JvmField
    val NONE = PluginBundlingRestrictions(OsFamily.ALL, JvmArchitecture.ALL, false)
  }

  init {
    if (supportedOs == OsFamily.ALL && supportedArch != JvmArchitecture.ALL) {
      error("os-independent, but arch-dependent plugins are currently not supported by build scripts")
    }
  }
}
