/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.diagnostic.hprof.analysis

import com.intellij.diagnostic.hprof.classstore.ClassDefinition
import com.intellij.diagnostic.hprof.navigator.ObjectNavigator
import com.intellij.diagnostic.hprof.util.HeapReportUtils.toPaddedShortStringAsSize
import com.intellij.diagnostic.hprof.util.HeapReportUtils.toShortStringAsCount
import com.intellij.diagnostic.hprof.util.HeapReportUtils.toShortStringAsSize
import com.intellij.diagnostic.hprof.util.TruncatingPrintBuffer
import it.unimi.dsi.fastutil.longs.LongArrayList
import it.unimi.dsi.fastutil.longs.LongList
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import java.util.*

internal class AnalyzeDisposer(private val analysisContext: AnalysisContext) {
  data class ClassGrouping(val childClass: ClassDefinition,
                           val parentClass: ClassDefinition?,
                           val rootClass: ClassDefinition)

  class InstanceStats {
    private val parentIds = LongArrayList()
    private val rootIds = LongOpenHashSet()

    fun parentCount() = LongOpenHashSet(parentIds).size
    fun rootCount() = rootIds.size
    fun objectCount() = parentIds.size

    fun registerObject(parentId: Long, rootId: Long) {
      parentIds.add(parentId)
      rootIds.add(rootId)
    }
  }

  data class DisposedDominatorReportEntry(val classDefinition: ClassDefinition, val count: Long, val size: Long)

  companion object {
    val TOP_REPORTED_CLASSES = setOf(
      "com.intellij.openapi.project.impl.ProjectImpl"
    )
  }

  fun prepareDisposerTreeSection(): String {
    if (!analysisContext.classStore.containsClass("com.intellij.openapi.util.Disposer")) {
      return ""
    }
    val nav = analysisContext.navigator

    val result = StringBuilder()

    nav.goToStaticField("com.intellij.openapi.util.Disposer", "ourTree")
    assert(!nav.isNull())
    nav.goToInstanceField("com.intellij.openapi.util.ObjectTree", "myRootNode")
    val rootObjectNodeIds:LongList = getObjectNodeChildrenIds(nav)

    val groupingToObjectStats = HashMap<ClassGrouping, InstanceStats>()
    val maxTreeDepth = 200
    val tooDeepObjectClasses = HashSet<ClassDefinition>()
    for (i in 0 until rootObjectNodeIds.size) {
      val rootObjectNodeId = rootObjectNodeIds.getLong(i)
      nav.goTo(rootObjectNodeId)
      if (nav.isNull()) continue
      val rootObjectId = nav.getInstanceFieldObjectId("com.intellij.openapi.util.ObjectNode", "myObject")
      val rootObjectClass = nav.getClassForObjectId(rootObjectId)
      nav.goTo(rootObjectNodeId)
      visitObjectTreeRecursively(nav, rootObjectNodeId, 0L, null, rootObjectId, rootObjectClass,
                                 tooDeepObjectClasses, maxTreeDepth, 0, groupingToObjectStats)
    }

    TruncatingPrintBuffer(400, 0, result::appendLine).use { buffer ->
      groupingToObjectStats
        .entries
        .asSequence()
        .sortedByDescending { it.value.objectCount() }
        .groupBy { it.key.rootClass }
        .forEach { (rootClass, entries) ->
          buffer.println("Root: ${rootClass.name}")
          TruncatingPrintBuffer(100, 0, buffer::println).use { buffer ->
            entries.forEach { (classGrouping, instanceStats) ->
              printDisposerTreeReportLine(buffer, classGrouping, instanceStats)
            }
          }
          buffer.println()
        }
    }

    if (tooDeepObjectClasses.size > 0) {
      result.appendLine("Skipped analysis of objects too deep in disposer tree:")
      tooDeepObjectClasses.forEach {
        result.appendLine(" * ${nav.classStore.getShortPrettyNameForClass(it)}")
      }
    }

    return result.toString()
  }

  // list of ids of ObjectNodes stored in the current ObjectNode.myChildren
  private fun getObjectNodeChildrenIds(nav: ObjectNavigator): LongList {
    nav.goToInstanceField("com.intellij.openapi.util.ObjectNode", "myChildren")
    if (nav.getClass().name == Collections.emptyList<Any>().javaClass.name) {
      // no children
      return LongList.of()
    }
    else {
      nav.goToInstanceField("com.intellij.util.SmartList", "myElem")
      if (nav.isNull()) return LongList.of()
      if (nav.getClass().isArray()) {
        return nav.getReferencesCopy()
      }
      else {
        // myElem is ObjectNode
        return LongList.of(nav.id)
      }
    }
  }

  // visit ObjectTree starting with the ObjectNode pointed to by currentNodeId,
  // descending recursively through the "ObjectNode.myChildren" references,
  // gathering statistics into "groupingToObjectStats" along the way
  private fun visitObjectTreeRecursively(nav: ObjectNavigator,
                                         currentNodeId: Long,
                                         parentObjectId: Long,
                                         parentObjectClass: ClassDefinition?,
                                         rootObjectId: Long,
                                         rootObjectClass: ClassDefinition,
                                         tooDeepObjectClasses: HashSet<ClassDefinition>,
                                         maxTreeDepth: Int,
                                         currentTreeDepth: Int,
                                         groupingToObjectStats: HashMap<ClassGrouping, InstanceStats>) {
    nav.goTo(currentNodeId)
    if (nav.isNull()) return
    val currentObjectId = nav.getInstanceFieldObjectId("com.intellij.openapi.util.ObjectNode", "myObject")
    val currentObjectClass = nav.getClassForObjectId(currentObjectId)

    groupingToObjectStats
      .getOrPut(ClassGrouping(currentObjectClass, parentObjectClass, rootObjectClass)) { InstanceStats() }
      .registerObject(parentObjectId, rootObjectId)

    if (currentTreeDepth >= maxTreeDepth) {
      tooDeepObjectClasses.add(currentObjectClass)
      return
    }

    nav.goTo(currentNodeId)
    val childrenNodeIds = getObjectNodeChildrenIds(nav)
    for (i in 0 until childrenNodeIds.size) {
      val childNodeId = childrenNodeIds.getLong(i)
      visitObjectTreeRecursively(nav, childNodeId, currentObjectId, currentObjectClass, rootObjectId, rootObjectClass,
                                 tooDeepObjectClasses, maxTreeDepth, currentTreeDepth + 1, groupingToObjectStats)
    }
  }

  private fun printDisposerTreeReportLine(buffer: TruncatingPrintBuffer,
                                          classGrouping: ClassGrouping,
                                          instanceStats: InstanceStats) {
    val (sourceClass, parentClass, rootClass) = classGrouping
    val nav = analysisContext.navigator

    val objectCount = instanceStats.objectCount()
    val parentCount = instanceStats.parentCount()

    // Ignore 1-1 mappings
    if (parentClass != null && objectCount == parentCount)
      return

    val parentString: String
    if (parentClass == null) {
      parentString = "(no parent)"
    }
    else {
      val parentClassName = nav.classStore.getShortPrettyNameForClass(parentClass)
      val rootCount = instanceStats.rootCount()
      if (rootClass != parentClass || rootCount != parentCount) {
        parentString = "<-- $parentCount $parentClassName [...] $rootCount"
      }
      else
        parentString = "<-- $parentCount"
    }

    val sourceClassName = nav.classStore.getShortPrettyNameForClass(sourceClass)
    buffer.println("  ${String.format("%6d", objectCount)} $sourceClassName $parentString")
  }

  fun computeDisposedObjectsIDs() {
    val disposedObjectsIDs = analysisContext.disposedObjectsIDs
    disposedObjectsIDs.clear()

    val nav = analysisContext.navigator
    val parentList = analysisContext.parentList

    if (!nav.classStore.containsClass("com.intellij.openapi.util.Disposer")) {
      return
    }

    nav.goToStaticField("com.intellij.openapi.util.Disposer", "ourTree")
    assert(!nav.isNull())
    nav.goToInstanceField("com.intellij.openapi.util.ObjectTree", "myDisposedObjects")
    nav.goToInstanceField("com.intellij.util.containers.WeakHashMap", "myMap")
    nav.goToInstanceField("com.intellij.util.containers.RefHashMap\$MyMap", "keys")
    val weakKeyClass = nav.classStore["com.intellij.util.containers.WeakHashMap\$WeakKey"]

    nav.getReferencesCopy().forEach {
      if (it == 0L) {
        return@forEach
      }
      nav.goTo(it, ObjectNavigator.ReferenceResolution.ALL_REFERENCES)
      if (nav.getClass() != weakKeyClass) {
        return@forEach
      }
      nav.goToInstanceField("com.intellij.util.containers.WeakHashMap\$WeakKey", "referent")
      if (nav.id == 0L) {
        return@forEach
      }

      val leakId = nav.id.toInt()
      if (parentList[leakId] == 0) {
        // If there is no parent, then the object does not have a strong-reference path to GC root
        return@forEach
      }
      disposedObjectsIDs.add(leakId)
    }
  }

  fun prepareDisposedObjectsSection(): String {
    val result = StringBuilder()

    val leakedInstancesByClass = HashMap<ClassDefinition, LongList>()
    val countByClass = Object2IntOpenHashMap<ClassDefinition>()
    var totalCount = 0

    val nav = analysisContext.navigator
    val disposedObjectsIDs = analysisContext.disposedObjectsIDs
    val disposerOptions = analysisContext.config.disposerOptions

    disposedObjectsIDs.forEach {
      nav.goTo(it.toLong(), ObjectNavigator.ReferenceResolution.ALL_REFERENCES)

      val leakClass = nav.getClass()
      val leakId = nav.id

      leakedInstancesByClass.computeIfAbsent(leakClass) { LongArrayList() }.add(leakId)

      countByClass.put(leakClass, countByClass.getInt(leakClass) + 1)
      totalCount++
    }

    // Convert TObjectIntHashMap to list of entries
    data class TObjectIntMapEntry<T>(val key: T, val value: Int)

    val entries = mutableListOf<TObjectIntMapEntry<ClassDefinition>>()
    countByClass.object2IntEntrySet().fastForEach {
      entries.add(TObjectIntMapEntry(it.key, it.intValue))
    }

    if (disposerOptions.includeDisposedObjectsSummary) {
      // Print counts of disposed-but-strong-referenced objects
      TruncatingPrintBuffer(100, 0, result::appendln).use { buffer ->
        buffer.println("Count of disposed-but-strong-referenced objects: $totalCount")
        entries
          .sortedByDescending { it.value }
          .partition { TOP_REPORTED_CLASSES.contains(it.key.name) }
          .let { it.first + it.second }
          .forEach { entry ->
            buffer.println("  ${entry.value} ${entry.key.prettyName}")
          }
      }
      result.appendln()
    }

    val disposedTree = GCRootPathsTree(analysisContext, AnalysisConfig.TreeDisplayOptions.all(), null)
    val iterator = disposedObjectsIDs.iterator()
    while (iterator.hasNext()) {
      disposedTree.registerObject(iterator.nextInt())
    }

    val disposedDominatorNodesByClass = disposedTree.getDisposedDominatorNodes()
    var allDominatorsCount = 0L
    var allDominatorsSubgraphSize = 0L
    val disposedDominatorClassSizeList = mutableListOf<DisposedDominatorReportEntry>()
    disposedDominatorNodesByClass.forEach { (classDefinition, nodeList) ->
      var dominatorClassSubgraphSize = 0L
      var dominatorClassInstanceCount = 0L
      nodeList.forEach {
        dominatorClassInstanceCount += it.instances.size
        dominatorClassSubgraphSize += it.totalSizeInDwords.toLong() * 4
      }
      allDominatorsCount += dominatorClassInstanceCount
      allDominatorsSubgraphSize += dominatorClassSubgraphSize
      disposedDominatorClassSizeList.add(
        DisposedDominatorReportEntry(classDefinition, dominatorClassInstanceCount, dominatorClassSubgraphSize))
    }

    if (disposerOptions.includeDisposedObjectsSummary) {
      TruncatingPrintBuffer(30, 0, result::appendln).use { buffer ->
        buffer.println("Disposed-but-strong-referenced dominator object count: $allDominatorsCount")
        buffer.println(
          "Disposed-but-strong-referenced dominator sub-graph size: ${toShortStringAsSize(allDominatorsSubgraphSize)}")
        disposedDominatorClassSizeList
          .sortedByDescending { it.size }
          .forEach { entry ->
            buffer.println(
              "  ${toPaddedShortStringAsSize(entry.size)} - ${toShortStringAsCount(entry.count)} ${entry.classDefinition.name}")
          }
      }
      result.appendln()
    }

    if (disposerOptions.includeDisposedObjectsDetails) {
      val instancesListInOrder = getInstancesListInPriorityOrder(
        leakedInstancesByClass,
        disposedDominatorClassSizeList
      )

      TruncatingPrintBuffer(700, 0, result::appendln).use { buffer ->
        instancesListInOrder
          .forEach { instances ->
            nav.goTo(instances.getLong(0))
            buffer.println(
              "Disposed but still strong-referenced objects: ${instances.size} ${nav.getClass().prettyName}, most common paths from GC-roots:")
            val gcRootPathsTree = GCRootPathsTree(analysisContext, disposerOptions.disposedObjectsDetailsTreeDisplayOptions, nav.getClass())
            instances.forEach { leakId ->
              gcRootPathsTree.registerObject(leakId.toInt())
            }
            gcRootPathsTree.printTree().lineSequence().forEach(buffer::println)
          }
      }
    }
    return result.toString()
  }

  private fun getInstancesListInPriorityOrder(
    classToLeakedIdsList: HashMap<ClassDefinition, LongList>,
    disposedDominatorReportEntries: List<DisposedDominatorReportEntry>): List<LongList> {
    val result = mutableListOf<LongList>()

    // Make a mutable copy. When a class instances are added to the result list, remove the class entry from the copy.
    val classToLeakedIdsListCopy = HashMap(classToLeakedIdsList)

    // First, all top classes
    TOP_REPORTED_CLASSES.forEach { topClassName ->
      classToLeakedIdsListCopy
        .filterKeys { it.name == topClassName }
        .forEach { (classDefinition, list) ->
          result.add(list)
          classToLeakedIdsListCopy.remove(classDefinition)
        }
    }

    // Alternate between class with most instances leaked and class with most bytes leaked

    // Prepare instance count class list by priority
    val classOrderByInstanceCount = ArrayDeque<ClassDefinition>(
      classToLeakedIdsListCopy
        .entries
        .sortedByDescending { it.value.size }
        .map { it.key }
    )

    // Prepare dominator bytes count class list by priority
    val classOrderByByteCount = ArrayDeque<ClassDefinition>(
      disposedDominatorReportEntries
        .sortedByDescending { it.size }
        .map { it.classDefinition }
    )

    // zip, but ignore duplicates
    var nextByInstanceCount = true
    while (!classOrderByInstanceCount.isEmpty() ||
           !classOrderByByteCount.isEmpty()) {
      val nextCollection = if (nextByInstanceCount) classOrderByInstanceCount else classOrderByByteCount
      if (!nextCollection.isEmpty()) {
        val nextClass = nextCollection.removeFirst()
        val list = classToLeakedIdsListCopy.remove(nextClass) ?: continue
        result.add(list)
      }
      nextByInstanceCount = !nextByInstanceCount
    }
    return result
  }

}