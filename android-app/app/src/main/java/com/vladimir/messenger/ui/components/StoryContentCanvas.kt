package com.vladimir.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vladimir.messenger.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One 360x640 artwork for both editing and playback. The keyboard scales the
 * whole canvas, not its text width: wrapping, margins and photo Fit stay stable.
 * Story artwork has fixed typography (like an image), independent of device
 * font scale; the surrounding app controls retain accessibility font scaling.
 */
@Composable
internal fun StoryContentCanvas(
    text: String,
    photoB64: String?,
    background: Brush,
    modifier: Modifier = Modifier,
    onTextChange: ((String) -> Unit)? = null,
    photoCircle: Boolean = false,
) {
    val density = LocalDensity.current
    val width = with(density) { 360.dp.roundToPx() }
    val height = with(density) { 640.dp.roundToPx() }
    Layout(
        modifier = modifier,
        content = {
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(background)) {
                    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                        if (photoB64 != null) {
                            if (photoCircle) StoryPhotoView(photoB64,
                                Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape), ContentScale.Crop)
                            else StoryPhotoView(photoB64, Modifier.fillMaxSize())
                        }
                        if (onTextChange != null || text.isNotEmpty()) {
                            StoryCanvasText(text, photoB64 != null, onTextChange)
                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val scale = min(
            if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() / width else 1f,
            if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() / height else 1f,
        ).coerceAtLeast(0f)
        val child = measurables.single().measure(Constraints.fixed(width, height))
        layout(constraints.constrainWidth((width * scale).roundToInt()),
            constraints.constrainHeight((height * scale).roundToInt())) {
            child.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

@Composable
private fun BoxScope.StoryCanvasText(text: String, caption: Boolean, onTextChange: ((String) -> Unit)?) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val inheritedStyle = LocalTextStyle.current
    val baseSize = if (caption) 20f else if (text.length > 120) 25f else 32f
    val lineRatio = if (caption) 1.4f else if (text.length > 120) 1.36f else 1.3125f
    // Caption occupies at most 40% of the inner artwork; plaintext can use all.
    // Match each rounded padding operation, including fractional screen densities.
    val inset = with(density) { 28.dp.roundToPx() }
    val captionInset = if (caption) with(density) { 12.dp.roundToPx() } else 0
    val availableWidth = with(density) { 360.dp.roundToPx() } - 2 * inset - 2 * captionInset
    val innerHeight = with(density) { 640.dp.roundToPx() } - 2 * inset
    val availableHeight = (if (caption) (innerHeight * .4f).toInt() else innerHeight) - 2 * captionInset
    fun style(size: Float) = inheritedStyle.merge(TextStyle(color = Color.White, fontSize = size.sp,
        letterSpacing = 0.sp,
        lineHeight = (size * lineRatio).sp,
        fontWeight = if (caption) FontWeight.Normal else FontWeight.SemiBold,
        textAlign = TextAlign.Center))
    val textStyle = remember(text, caption, density, inheritedStyle, measurer) {
        fun fits(size: Float): Boolean = !measurer.measure(text.ifEmpty { " " }, style(size),
            constraints = Constraints(maxWidth = availableWidth, maxHeight = availableHeight)).hasVisualOverflow
        if (fits(baseSize)) style(baseSize) else {
            // Bounded search also handles many explicit newlines without clipping.
            var low = .5f
            var high = baseSize
            repeat(8) {
                val middle = (low + high) / 2
                if (fits(middle)) low = middle else high = middle
            }
            style(low)
        }
    }
    val fieldModifier = Modifier.align(if (caption) Alignment.BottomCenter else Alignment.Center)
        .fillMaxWidth()
        .then(if (caption) Modifier.background(Color.Black.copy(alpha = .55f), RoundedCornerShape(16.dp))
            .padding(12.dp) else Modifier)
    if (onTextChange != null) {
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = fieldModifier.testTag("story-editor"),
            textStyle = textStyle,
            cursorBrush = SolidColor(Color(0xFFE6BE70)),
            singleLine = false,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Text(
                        stringResource(if (caption) R.string.story_caption else R.string.story_placeholder),
                        style = textStyle.copy(color = Color.White.copy(alpha = .6f)),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    inner()
                }
            },
        )
    } else {
        Text(text, modifier = fieldModifier.testTag("story-preview"), style = textStyle)
    }
}
