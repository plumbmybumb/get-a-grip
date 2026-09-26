// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.ui.theme.DarkPalette
import run.nuri.getagrip.ui.theme.GripPalette
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.Motion
import run.nuri.getagrip.ui.theme.instrumentRim
import run.nuri.getagrip.ui.theme.rememberReduceMotion

// THE SESSION STAGE — the anatomy every measuring screen shares (iOS `RunnerView`'s stacked
// layout, `GaugeView`, `CriticalForceTestView`, `MaxMeasureView`): the phase colour washing
// down from the top of the screen, the numbers on ONE panel, the curve running in the clear
// between panel and dock, stretched to the screen's edges, and every action on ONE dock.
//
// TRANSLATION NOTE: iOS draws the panel and the dock as Liquid Glass refracting the wash.
// Android has no runtime blur by the owner's call (older GPUs, see `Materials.kt`), so both
// are the house instrument material — a static, near-opaque fill with the lit hairline rim —
// and the PANEL takes the phase tint into its own fill, standing in for the colour the glass
// would have picked up from the wash beneath it. The wash, the fills and the rim are drawn
// from `drawBehind`, reading animated values in the DRAW phase: a phase change repaints them
// without recomposing a single number.

/// The shared shape and spacing, so the panel and the dock cannot stop matching.
object InstrumentStage {
    /// Sheet radius, not card radius: a surface floating over content is the system's sheet
    /// vocabulary, and beside 48 dp capsules a 22 dp corner reads tight (iOS `RunnerGlass`).
    val shape = RoundedCornerShape(Metrics.radiusSheet)
    /// The dock's inset and the gap between its actions. iOS spends 8; a compact Android phone
    /// at a large font scale wraps the dock to three rows, and those points go to the graph.
    val dockSpacing = 6.dp
    /// The floating shadow: light, because the surface must still read as thin.
    val shadowElevation = 3.dp
    /// How strongly the wash starts at the top of the screen before fading out at the
    /// open graph's upper edge (iOS `PhaseWash`, 0.30).
    const val WASH_ALPHA = 0.26f
    /// How much of the phase colour's HUE the panel's own fill carries.
    const val PANEL_TINT_ALPHA = 0.12f
    /// The fill is near-opaque on purpose: nothing under it moves (the curve runs in the open
    /// region, never under the panel), and a translucent surface over a static wash buys only
    /// a muddier number.
    const val SURFACE_ALPHA = 0.96f

    /// The panel's fill for a phase tint (null: untinted, as the dock).
    ///
    /// **The tint moves the HUE, never the lightness.** Mixed plainly, dark mode's light bleu
    /// lifted the card enough to drop tertiary ink to 3.9:1 (`ContrastTests`), and light mode's
    /// deep hues darkened it the other way. So the mix is rescaled, in linear light, back to the
    /// card's own luminance: every ink keeps exactly the contrast it has on a card, in every
    /// phase, and only the colour says which phase it is.
    fun panelFill(palette: GripPalette, tint: Color?): Color {
        val base = if (tint == null) palette.card else hueOnly(palette.card, tint, PANEL_TINT_ALPHA)
        return base.copy(alpha = SURFACE_ALPHA)
    }

    private fun hueOnly(card: Color, tint: Color, amount: Float): Color {
        fun toLinear(c: Float) = if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        fun toSrgb(c: Float) = if (c <= 0.0031308f) c * 12.92f
            else (1.055 * Math.pow(c.toDouble(), 1 / 2.4) - 0.055).toFloat()
        fun luminance(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val cr = toLinear(card.red); val cg = toLinear(card.green); val cb = toLinear(card.blue)
        val r = cr + (toLinear(tint.red) - cr) * amount
        val g = cg + (toLinear(tint.green) - cg) * amount
        val b = cb + (toLinear(tint.blue) - cb) * amount
        val mixed = luminance(r, g, b)
        val scale = if (mixed > 0f) luminance(cr, cg, cb) / mixed else 1f
        return Color(
            toSrgb((r * scale).coerceIn(0f, 1f)),
            toSrgb((g * scale).coerceIn(0f, 1f)),
            toSrgb((b * scale).coerceIn(0f, 1f)),
        )
    }
}

/// Where the open graph starts, measured, so the wash can hang from the top of the screen
/// down to exactly that edge. Written from `onGloballyPositioned` and read ONLY in the draw
/// phase — a layout pass repaints the wash; it never recomposes the screen.
@Stable
class StageGeometry {
    internal var canvasTop by mutableFloatStateOf(Float.NaN)
    internal var regionTop by mutableFloatStateOf(Float.NaN)

    /// The wash's reach in the canvas's own pixels, or null until both edges are measured.
    internal fun washLength(): Float? {
        val canvas = canvasTop
        val region = regionTop
        if (canvas.isNaN() || region.isNaN()) return null
        return (region - canvas).coerceAtLeast(0f)
    }
}

@Composable
fun rememberStageGeometry(): StageGeometry = remember { StageGeometry() }

/// **The phase colour, washing down from the top of the screen** to the open graph's upper
/// edge: blue while the clock runs, amber while it waits on you, steel at rest, red when the
/// link is gone — so the top of the phone says the state before a word is read. Apply it to
/// the full-screen root (behind the system bars). `tint == null` draws nothing (gauge-free
/// sessions, which have no graph to hang it from). The colour change cross-fades on the
/// house curve.
fun Modifier.phaseWash(tint: Color?, geometry: StageGeometry): Modifier = composed {
    val reduceMotion = rememberReduceMotion()
    val color by animateColorAsState(tint ?: Color.Transparent, Motion.state(reduceMotion), label = "phase wash")
    this
        .onGloballyPositioned { geometry.canvasTop = it.positionInWindow().y }
        .drawBehind {
            if (color.alpha == 0f) return@drawBehind
            // Before the region is measured, a third of the screen: a sensible first frame.
            val length = geometry.washLength() ?: (size.height / 3f)
            if (length <= 0f) return@drawBehind
            drawRect(
                Brush.verticalGradient(
                    listOf(color.copy(alpha = InstrumentStage.WASH_ALPHA * color.alpha), Color.Transparent),
                    startY = 0f, endY = length,
                ),
                topLeft = Offset.Zero,
                size = Size(size.width, length),
            )
        }
}

/// **The information panel** — the screen's numbers on one surface floating over the wash,
/// tinted by the phase. `overlay` draws above the content and the rim (the runner's
/// grip-change outline lives here, because the panel is where the grip is NAMED).
@Composable
fun InstrumentPanel(
    tint: Color?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 12.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalGripPalette.current
    val reduceMotion = rememberReduceMotion()
    val fill by animateColorAsState(InstrumentStage.panelFill(palette, tint), Motion.state(reduceMotion),
        label = "panel fill")
    StageSurface(modifier, fill = { fill }) {
        Column(
            Modifier.fillMaxWidth().padding(contentPadding),
            verticalArrangement = verticalArrangement,
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
        overlay?.invoke(this)
    }
}

/// **The dock** — every action on ONE surface, each in a quiet ink well (`DockButton`), at
/// most one tinted (`DockTintedButton`). Glass on glass is the layering iOS asks you not to
/// do; a card on a card is the same mistake here.
@Composable
fun InstrumentDock(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalGripPalette.current
    val fill = InstrumentStage.panelFill(palette, null)
    StageSurface(modifier, fill = { fill }) {
        Column(
            Modifier.fillMaxWidth().padding(InstrumentStage.dockSpacing),
            verticalArrangement = Arrangement.spacedBy(InstrumentStage.dockSpacing),
            content = content,
        )
    }
}

/// The one drawing both surfaces use: shadow, fill (read in the draw phase) and the lit rim.
@Composable
private fun StageSurface(modifier: Modifier, fill: () -> Color, content: @Composable BoxScope.() -> Unit) {
    val palette = LocalGripPalette.current
    val shape = InstrumentStage.shape
    val rim = instrumentRim(palette)
    val shadow = Color.Black.copy(alpha = if (palette == DarkPalette) 0.5f else 0.22f)
    Box(
        modifier
            .widthIn(max = Metrics.maxContentWidth)
            .fillMaxWidth()
            .shadow(InstrumentStage.shadowElevation, shape, clip = false, ambientColor = shadow, spotColor = shadow)
            // A surface keeps its content: a 10 000-pull routine's pills must not run past the rim.
            .clip(shape)
            .drawBehind {
                drawOutline(shape.createOutline(size, layoutDirection, this), fill())
            }
            .border(rim, shape),
        content = content,
    )
}

/// **The open graph** — the stretch of screen between the panel and the dock where the curve
/// runs in the clear. `trace` is drawn behind `content`, stretched past the column's side
/// padding to the screen's edges (iOS `.padding(.horizontal, -Metrics.hPadding)`); its top
/// edge is where the phase wash ends.
@Composable
fun OpenGraphRegion(
    geometry: StageGeometry,
    modifier: Modifier = Modifier,
    bleed: Dp = Metrics.hPadding,
    trace: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier.onGloballyPositioned { geometry.regionTop = it.positionInWindow().y },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.matchParentSize().horizontalBleed(bleed), content = trace)
        content()
    }
}

/// Lay a child out wider than its slot by `bleed` on each side, centred on it. Nothing clips
/// a Column's children, so the extra width draws past the side padding to the screen edge.
internal fun Modifier.horizontalBleed(bleed: Dp): Modifier = layout { measurable, constraints ->
    val extra = bleed.roundToPx()
    if (!constraints.hasBoundedWidth || extra <= 0) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val width = constraints.maxWidth + extra * 2
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}

// MARK: - Dock actions

/// A tinted dock action's colours (iOS `GlassTint`): the flat accent as a 22 % well, and the
/// tint's own legible ink ON it — never the accent itself as ink, which measured 2.5:1 in
/// dark mode on iOS, and never white on a solid fill, which would be a second kind of surface.
enum class DockTint {
    bleu, alarm, graphite;

    fun well(): Color = when (this) {
        bleu -> Color(0xFF318CE7)
        alarm -> Color(0xFFC62828)
        graphite -> Color(0xFF2B3038)
    }

    fun ink(palette: GripPalette): Color {
        val dark = palette == DarkPalette
        return when (this) {
            bleu -> if (dark) Color(0xFF9FCEFA) else Color(0xFF10508F)
            alarm -> if (dark) Color(0xFFFFA79E) else Color(0xFF8E1B1B)
            graphite -> if (dark) Color(0xFFEDF1F6) else Color(0xFF23272E)
        }
    }

    companion object {
        const val WELL_ALPHA = 0.22f
        const val DISABLED_WELL_ALPHA = 0.08f
    }
}

/// A dock action: FULL WIDTH in its slot, a quiet ink well on the dock (iOS `DockButton`).
/// A hugging button truncates "Pause" to "Pa…" three to a row.
///
/// Disabled dims AND disables; the LABEL is never swapped for the reason, which rides the
/// description instead (swapped labels made TalkBack read "Paused, dimmed. Paused.").
@Composable
fun DockButton(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = LocalGripPalette.current.inkPrimary,
    enabled: Boolean = true,
    disabledReason: String? = null,
    onClick: () -> Unit,
) {
    val palette = LocalGripPalette.current
    DockAction(
        title = title, icon = icon, enabled = enabled, disabledReason = disabledReason,
        well = palette.inkPrimary.copy(alpha = if (palette == DarkPalette) 0.08f else 0.06f),
        disabledWell = palette.inkPrimary.copy(alpha = 0.03f),
        ink = tint, modifier = modifier, onClick = onClick,
    )
}

/// The dock's one TINTED action (iOS `DockTintedButton`): bleu to start or connect, alarm to
/// stop, graphite to commit.
@Composable
fun DockTintedButton(
    title: String,
    tint: DockTint,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalGripPalette.current
    DockAction(
        title = title, icon = icon, enabled = enabled, disabledReason = null,
        well = tint.well().copy(alpha = DockTint.WELL_ALPHA),
        disabledWell = tint.well().copy(alpha = DockTint.DISABLED_WELL_ALPHA),
        ink = tint.ink(palette), modifier = modifier, onClick = onClick,
    )
}

@Composable
private fun DockAction(
    title: String,
    icon: ImageVector?,
    enabled: Boolean,
    disabledReason: String?,
    well: Color,
    disabledWell: Color,
    ink: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalGripPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = well,
            contentColor = ink,
            disabledContainerColor = disabledWell,
            disabledContentColor = palette.inkTertiary.copy(alpha = 0.5f),
        ),
        elevation = null,
        contentPadding = PaddingValues(
            horizontal = Metrics.buttonHorizontalPadding, vertical = Metrics.buttonVerticalPadding),
        modifier = modifier
            .heightIn(min = Metrics.controlMinHeight)
            .pressFeedback(interactionSource)
            .semantics {
                if (!enabled && disabledReason != null) contentDescription = L10n.tr("%s. %s", title, disabledReason)
            },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/// A dock's quiet line of explanation (why Start did nothing, what happens next).
@Composable
fun DockNote(text: String, modifier: Modifier = Modifier, color: Color = LocalGripPalette.current.inkSecondary) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        color = color,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 6.dp),
    )
}
