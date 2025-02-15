// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build.fus

import com.intellij.internal.statistic.config.EventLogExternalSettings
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import org.jetbrains.intellij.build.BuildContext
import org.jetbrains.intellij.build.BuildOptions
import org.jetbrains.intellij.build.TraceManager.spanBuilder
import org.jetbrains.intellij.build.impl.ModuleOutputPatcher
import org.jetbrains.intellij.build.impl.createSkippableTask
import java.util.concurrent.ForkJoinTask

/**
 * Download a default version of feature usage statistics metadata to be bundled with IDE.
 */
internal fun createStatisticsRecorderBundledMetadataProviderTask(moduleOutputPatcher: ModuleOutputPatcher,
                                                                 context: BuildContext): ForkJoinTask<*>? {
  val featureUsageStatisticsProperties = context.proprietaryBuildTools.featureUsageStatisticsProperties ?: return null
  return createSkippableTask(
    spanBuilder("bundle a default version of feature usage statistics"),
    BuildOptions.FUS_METADATA_BUNDLE_STEP,
    context
  ) {
    try {
      val recorderId = featureUsageStatisticsProperties.recorderId
      moduleOutputPatcher.patchModuleOutput(
        moduleName = "intellij.platform.ide.impl",
        path = "resources/event-log-metadata/$recorderId/events-scheme.json",
        content = download(appendProductCode(metadataServiceUri(featureUsageStatisticsProperties, context), context))
      )
    }
    catch (e: Throwable) {
      // do not halt build, just record exception
      val span = Span.current()
      span.recordException(RuntimeException("Failed to bundle default version of feature usage statistics metadata", e))
      span.setStatus(StatusCode.ERROR)
    }
  }
}


private fun appendProductCode(uri: String, context: BuildContext): String {
  val name = context.applicationInfo.productCode + ".json"
  return if (uri.endsWith('/')) "$uri$name" else "$uri/$name"
}

private fun download(url: String): ByteArray {
  Span.current().addEvent("download", Attributes.of(AttributeKey.stringKey("url"), url))
  return org.jetbrains.intellij.build.io.download(url)
}

private fun metadataServiceUri(featureUsageStatisticsProperties: FeatureUsageStatisticsProperties, context: BuildContext): String {
  val providerUri = appendProductCode(featureUsageStatisticsProperties.metadataProviderUri, context)
  Span.current().addEvent("parsing", Attributes.of(AttributeKey.stringKey("url"), providerUri))
  val appInfo = context.applicationInfo
  val settings = EventLogExternalSettings.parseSendSettings(download(providerUri).inputStream().reader(),
                                                            "${appInfo.majorVersion}.${appInfo.minorVersion}")
  return settings.getEndpoint("metadata")!!
}
