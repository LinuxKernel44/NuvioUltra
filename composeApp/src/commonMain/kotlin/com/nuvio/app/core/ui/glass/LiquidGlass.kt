package com.nuvio.app.core.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.isRuntimeShaderSupported
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.nuvio.app.features.settings.ThemeSettingsRepository

/**
 * App-wide "liquid glass" engine.
 *
 * One [LayerBackdrop] records the screen content ([liquidGlassSource]); surfaces that float *above*
 * that content (nav bar, sheets, dialogs, menus, toasts) sample it with blur + lens refraction +
 * vibrancy ([GlassTier.Refractive]). Surfaces that live *inside* the content (cards, chips, buttons,
 * fields) cannot sample themselves, so they get the cheap [GlassTier.Lite] look: translucent body,
 * specular rim, inner shadow.
 */
@Stable
class LiquidGlassState internal constructor(
    internal val backdrop: LayerBackdrop,
    /** 0f (off) .. 1f (max). */
    val intensity: Float,
) {
    val active: Boolean get() = intensity > 0f
}

val LocalLiquidGlass = staticCompositionLocalOf<LiquidGlassState?> { null }

enum class GlassTier { Refractive, Lite }

/** Visual recipe of a glass surface. Sizes in dp, alphas 0..1 at full intensity. */
enum class GlassStyle(
    internal val blur: Float,
    internal val lensHeight: Float,
    internal val lensAmount: Float,
    internal val depth: Boolean,
    internal val bodyAlpha: Float,
    internal val rimAlpha: Float,
    internal val shadowRadius: Float,
) {
    /** Floating bars, sheets: strong refraction. */
    Bar(blur = 6f, lensHeight = 16f, lensAmount = 32f, depth = true, bodyAlpha = 0.10f, rimAlpha = 0.9f, shadowRadius = 20f),
    /** Large panels: dialogs, menus, cards. Gentle refraction. */
    Panel(blur = 14f, lensHeight = 10f, lensAmount = 14f, depth = false, bodyAlpha = 0.14f, rimAlpha = 0.7f, shadowRadius = 16f),
    /** Text-heavy floating surfaces (sheets, dialogs, menus): heavy frost so content stays readable. */
    Sheet(blur = 32f, lensHeight = 12f, lensAmount = 20f, depth = false, bodyAlpha = 0.66f, rimAlpha = 0.8f, shadowRadius = 24f),
    /** Small controls: round buttons, chips, badges. */
    Control(blur = 3f, lensHeight = 8f, lensAmount = 16f, depth = false, bodyAlpha = 0.12f, rimAlpha = 0.85f, shadowRadius = 8f),
}

private val DefaultShape = RoundedCornerShape(24.dp)
private val GlassBaseColor = Color(0xFF1C1C1E)

/** Hosts the shared backdrop. Content that should be refracted must apply [Modifier.liquidGlassSource]. */
@Composable
fun ProvideLiquidGlass(content: @Composable () -> Unit) {
    val level by ThemeSettingsRepository.liquidGlassUiLevel.collectAsState()
    val backdrop = rememberLayerBackdrop()
    val state = remember(backdrop, level) { LiquidGlassState(backdrop, level / 100f) }
    CompositionLocalProvider(LocalLiquidGlass provides state, content = content)
}

/** Marks this subtree as what floating glass surfaces see through themselves. */
@Composable
fun Modifier.liquidGlassSource(): Modifier {
    val state = LocalLiquidGlass.current ?: return this
    return if (state.active) layerBackdrop(state.backdrop) else this
}

/**
 * Turns a surface into liquid glass. When the feature is off (or unavailable) this degrades to the
 * plain [fallbackColor] background, i.e. exactly the pre-glass look.
 *
 * @param tint optional accent mixed into the glass body (selected chips, primary buttons).
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape = DefaultShape,
    style: GlassStyle = GlassStyle.Panel,
    tier: GlassTier = GlassTier.Lite,
    fallbackColor: Color = Color(0xFF1C1C1E),
    tint: Color = Color.Unspecified,
): Modifier {
    val state = LocalLiquidGlass.current
    if (state == null || !state.active) {
        return if (fallbackColor == Color.Unspecified) this else background(fallbackColor, shape)
    }
    val k = state.intensity
    val refractive = tier == GlassTier.Refractive
    val backdrop = if (refractive) state.backdrop else emptyBackdrop()
    val lens = refractive && isRuntimeShaderSupported()
    val blurs = refractive && isRenderEffectSupported()
    // Without a real backdrop the body has to carry more of the look.
    val bodyAlpha = (if (refractive && blurs) style.bodyAlpha else style.bodyAlpha + 0.38f).coerceAtMost(0.95f)
    val base = if (fallbackColor.alpha < 0.05f || fallbackColor == Color.Unspecified) GlassBaseColor else fallbackColor.copy(alpha = 1f)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            if (refractive) {
                vibrancy()
                blur(style.blur.dp.toPx() * k)
                if (lens) {
                    lens(
                        style.lensHeight.dp.toPx() * k,
                        style.lensAmount.dp.toPx() * k,
                        depthEffect = style.depth,
                        chromaticAberration = k > 0.6f,
                    )
                }
            }
        },
        highlight = {
            Highlight(
                width = 0.75f.dp,
                alpha = (style.rimAlpha * k).coerceIn(0f, 1f),
                style = HighlightStyle.Default(),
            )
        },
        shadow = if (refractive) {
            { Shadow(radius = style.shadowRadius.dp, color = Color.Black.copy(alpha = 0.22f * k)) }
        } else {
            null
        },
        innerShadow = { InnerShadow(radius = 10f.dp, offset = androidx.compose.ui.unit.DpOffset(0.dp, 3.dp), color = Color.White.copy(alpha = 0.10f * k), blendMode = BlendMode.Plus) },
        onDrawSurface = { drawGlassBody(base, bodyAlpha, tint, k) },
    )
}

private fun DrawScope.drawGlassBody(base: Color, bodyAlpha: Float, tint: Color, k: Float) {
    drawRect(base.copy(alpha = bodyAlpha))
    drawRect(
        brush = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.10f * k), Color.White.copy(alpha = 0.01f * k)),
        ),
    )
    if (tint != Color.Unspecified) {
        drawRect(tint.copy(alpha = tint.alpha * 0.55f * k))
    }
}

/** Convenience for places that need the glass-vs-opaque decision (e.g. choose a container color). */
@Composable
fun isLiquidGlassActive(): Boolean = LocalLiquidGlass.current?.active == true

/** Blends [color] with transparency when glass is active so what's behind shows through. */
@Composable
fun glassContainerColor(color: Color, glassAlpha: Float = 0f): Color =
    if (isLiquidGlassActive()) color.copy(alpha = glassAlpha) else color
