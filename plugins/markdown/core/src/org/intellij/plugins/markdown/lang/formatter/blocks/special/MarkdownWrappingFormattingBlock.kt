// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.intellij.plugins.markdown.lang.formatter.blocks.special

import com.intellij.formatting.*
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.codeStyle.CodeStyleSettings
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes
import org.intellij.plugins.markdown.lang.formatter.blocks.MarkdownBlocks
import org.intellij.plugins.markdown.lang.formatter.blocks.MarkdownFormattingBlock
import org.intellij.plugins.markdown.lang.formatter.blocks.MarkdownTextUtil.isPunctuation
import org.intellij.plugins.markdown.lang.psi.util.children

/**
 * Markdown special formatting block that puts all it [MarkdownTokenTypes.TEXT] children inside wrap.
 *
 * Allows wrapping paragraphs around right margin. So, it kind of emulates reflow formatting for paragraphs.
 */
internal class MarkdownWrappingFormattingBlock(
  settings: CodeStyleSettings,
  spacing: SpacingBuilder,
  node: ASTNode,
  alignment: Alignment? = null,
  wrap: Wrap? = null
) : MarkdownFormattingBlock(node, settings, spacing, alignment, wrap) {
  /** Number of newlines in this block's text */
  val newlines: Int
    get() = node.text.count { it == '\n' }

  override fun buildChildren(): List<Block> {
    val customSettings = obtainCustomSettings()
    val wrapType = when {
      customSettings.WRAP_TEXT_IF_LONG -> WrapType.NORMAL
      else -> WrapType.NONE
    }
    val wrapping = Wrap.createWrap(wrapType, false)
    val filtered = MarkdownBlocks.filterFromWhitespaces(node.children())
    val result = ArrayList<Block>()
    for (node in filtered) {
      when (node.elementType) {
        MarkdownTokenTypes.TEXT -> processTextElement(result, node, wrapping)
        else -> result.add(MarkdownBlocks.create(node, settings, spacing) { alignment })
      }
    }
    return result
  }

  private fun processTextElement(result: MutableCollection<Block>, node: ASTNode, wrapping: Wrap) {
    val text = node.text
    val shift = node.textRange.startOffset
    val splits = splitTextForWrapping(text)
    for (split in splits) {
      // If there is a single split with punctuation character inside,
      // it means that it is surrounded by a non-text elements,
      // whitespaces, or it was at the start or end of the text.
      // In all of those cases the punctuation shouldn't be wrapped at all.
      // (had to check more than one character for '...'-like punctuation)
      val isPunctuation = split.subSequence(text).all { it.isPunctuation() }
      val actualWrapping = when {
        isPunctuation -> Wrap.createWrap(WrapType.NONE, false)
        else -> wrapping
      }
      val block = MarkdownRangedFormattingBlock(node, split.shiftRight(shift), settings, spacing, alignment, actualWrapping)
      result.add(block)
    }
  }

  companion object {
    private fun splitTextForWrapping(text: String): Sequence<TextRange> {
      return sequence {
        var start = -1
        var length = -1
        for ((index, char) in text.withIndex()) {
          if (char.isWhitespace()) {
            if (length > 0) {
              yield(TextRange.from(start, length))
            }
            start = -1
            length = -1
          }
          else {
            if (start == -1) {
              start = index
              length = 0
            }
            length++
          }
        }
        if (length > 0) {
          yield(TextRange.from(start, length))
        }
      }
    }
  }
}
