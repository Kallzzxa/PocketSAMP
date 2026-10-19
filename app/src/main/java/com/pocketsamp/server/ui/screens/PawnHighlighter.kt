package com.pocketsamp.server.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

private val HlKeyword = Color(0xFF00E5FF)   // cyan
private val HlPreproc = Color(0xFFFF9800)   // orange
private val HlComment = Color(0xFF6B7280)   // muted gray
private val HlString = Color(0xFF00E676)    // green
private val HlNumber = Color(0xFFFFD54F)    // yellow

private val CommentRegex = Regex("""/\*[\s\S]*?\*/|//[^\n]*""")
private val StringRegex = Regex(""""([^"\\\n]|\\.)*"""")
private val PreprocRegex = Regex("""(?m)^[ \t]*#[a-zA-Z]+\b""")
private val KeywordRegex =
    Regex("""\b(new|const|stock|public|forward|native|static|enum|if|else|for|while|do|switch|case|default|break|continue|return|goto|sizeof|defined|state|tagof)\b""")
private val NumberRegex = Regex("""\b(0x[0-9a-fA-F]+|\d[\d_]*\.?\d*)\b""")

/** Simple Pawn syntax highlighter: comments/strings first, then preprocessor, keywords, numbers. */
fun highlightPawn(code: String): AnnotatedString {
    val claimed = mutableListOf<IntRange>()
    fun isClaimed(r: IntRange): Boolean = claimed.any { it.first <= r.last && r.first <= it.last }

    return buildAnnotatedString {
        append(code)
        fun style(regex: Regex, color: Color, exclusive: Boolean) {
            for (m in regex.findAll(code)) {
                val r = m.range
                if (exclusive && isClaimed(r)) continue
                if (exclusive) claimed.add(r)
                addStyle(SpanStyle(color = color), r.first, r.last + 1)
            }
        }
        style(CommentRegex, HlComment, exclusive = true)
        style(StringRegex, HlString, exclusive = true)
        style(PreprocRegex, HlPreproc, exclusive = false)
        style(KeywordRegex, HlKeyword, exclusive = false)
        style(NumberRegex, HlNumber, exclusive = false)
    }
}

/** Keeps the text model plain while rendering highlighted code. */
class PawnHighlightTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        return TransformedText(highlightPawn(text.text), OffsetMapping.Identity)
    }
}
