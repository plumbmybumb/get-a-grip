// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.history

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.Fmt
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.store.DayLedger
import run.nuri.getagrip.store.LocalDayClock
import run.nuri.getagrip.ui.components.SubmissionState
import run.nuri.getagrip.ui.components.benchmarkBore
import run.nuri.getagrip.ui.components.climbNotch
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.preview.PreviewWorld
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics

/// A frozen share request. The month deck can keep moving while the sheet is open; what
/// gets exported remains the exact five-week window whose button was tapped.
data class ShareCalendarRequest(
    val days: List<DayStamp>,
    val ledger: DayLedger,
    val title: String,
    val today: DayStamp,
    val bestPull: ShareCalendarBestPull?,
)

/// A VALUE snapshot of the winning current max. The sheet observes no store, so recording a
/// max behind it cannot change the preview and the shared file out from under the person
/// looking at them.
data class ShareCalendarBestPull(
    val kg: Double,
    val grip: GripSpec,
    val side: Side,
    val recordedAt: Instant,
) {
    val sortKey: String get() = "${grip.key}|${side.rawValue}"

    val line: String
        get() {
            val hand = when (side) {
                Side.left -> L10n.tr(" · L")
                Side.right -> L10n.tr(" · R")
                Side.both -> ""
            }
            return L10n.tr("BEST PULL %s KG · %s%s", Fmt.fixed(kg, 1), grip.line, hand).uppercase()
        }
}

/// The three ways the card can be inked. Persisted, because whoever picks Dark once has a
/// camera roll that looks a particular way and should not have to re-pick it every month.
enum class ShareCardStyle(val rawValue: String, val title: String, val spoken: String) {
    white("white", L10n.tr("White"), L10n.tr("White ink")),
    dark("dark", L10n.tr("Dark ink"), L10n.tr("Dark ink")),
    frosted("frosted", L10n.tr("Frosted"), L10n.tr("Frosted card"));

    companion object {
        fun fromRaw(raw: String?): ShareCardStyle = entries.firstOrNull { it.rawValue == raw } ?: white
    }
}

/// Preview and export for one five-week History card.
///
/// TRANSLATION NOTE: iOS renders with `ImageRenderer(scale: 3)` and saves through
/// `PHPhotoLibrary`. Here the card is drawn once, into a `GraphicsLayer`, at a FORCED
/// density of 3 — see `EXPORT_DENSITY` — and saved through `MediaStore`, which needs no
/// permission at all from API 29 up. Sharing uses the system chooser.
///
/// **UNVERIFIED ON HARDWARE: the transparency.** iOS renders with `isOpaque = false` and
/// ships a PNG whose background — and whose punched notches and bores — are genuinely
/// clear. `GraphicsLayer.toImageBitmap()` returns an ARGB bitmap, so the alpha SHOULD
/// survive both the `BlendMode.Clear` glyphs and the card's own unpainted ground, but that
/// is a claim about the platform's capture path rather than something this file can prove.
/// Check one saved card against a dark and a light photo before shipping; if it comes back
/// with a black ground, the fix is to paint the ground rather than to change the glyphs.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareCalendarSheet(request: ShareCalendarRequest, onClose: () -> Unit) {
    val palette = LocalGripPalette.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    // Persisted per the iOS `@AppStorage("shareCardStyle")`. Held in a plain preference file
    // rather than the app's `SettingsStore`, which is not this wave's to extend.
    var style by remember { mutableStateOf(loadCardStyle(context)) }
    var includeBestPull by remember { mutableStateOf(true) }
    var saved by remember { mutableStateOf(false) }
    val exportSubmission = remember { SubmissionState() }
    var failure by remember { mutableStateOf<String?>(null) }

    val layer = rememberGraphicsLayer()

    // Any change to what the card SAYS invalidates a "Saved" receipt: the file in Photos is
    // no longer the picture on screen.
    LaunchedEffect(style, includeBestPull) { saved = false }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = palette.field,
        shape = RoundedCornerShape(topStart = Metrics.radiusSheet, topEnd = Metrics.radiusSheet),
    ) {
        Column(
            Modifier
                .padding(horizontal = Metrics.hPadding)
                .padding(bottom = Metrics.spacing),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(tr("Share calendar"), style = MaterialTheme.typography.titleLarge, color = palette.inkPrimary)

            CardPreview(request, style, includeBestPull, layer)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShareCardStyle.entries.forEach { option ->
                    FilterChip(
                        selected = style == option,
                        onClick = {
                            style = option
                            saveCardStyle(context, option)
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        label = { Text(option.title) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = palette.graphite,
                            selectedLabelColor = palette.graphiteInverse,
                            containerColor = palette.card,
                            labelColor = palette.inkPrimary,
                        ),
                        modifier = Modifier.semantics { contentDescription = option.spoken },
                    )
                }
            }

            if (request.bestPull != null) {
                // THE WHOLE ROW IS THE SWITCH — the same rule as the builder's
                // `ToggleRow`. A bare Switch beside a Text is two stops, the second of which
                // TalkBack can only call "Switch, off", and a 32 dp target beside a full-width
                // sentence nobody can tap.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .toggleable(
                            value = includeBestPull,
                            role = Role.Switch,
                            onValueChange = { includeBestPull = it },
                        ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Switch(
                        checked = includeBestPull,
                        // The row owns the tap and the semantics; a second handler here would
                        // fire the change twice.
                        onCheckedChange = null,
                    )
                    Text(
                        tr("Include your best pull"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = palette.inkPrimary,
                    )
                }
            }

            run.nuri.getagrip.ui.components.PrimaryButton(
                title = if (saved) tr("Saved to Photos") else tr("Save to Photos"),
                icon = if (saved) Icons.Filled.Check else Icons.Outlined.Download,
                enabled = !exportSubmission.isRunning,
            ) {
                val exportedStyle = style
                val exportedBestPull = includeBestPull
                exportSubmission.launch(scope) {
                    val png = capture(layer)
                    failure = if (png == null) context.tr(COULD_NOT_RENDER) else {
                        if (saveToPhotos(context, png, request.title)) {
                            saved = style == exportedStyle && includeBestPull == exportedBestPull
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            null
                        } else {
                            context.tr("Couldn't write to Photos.")
                        }
                    }
                }
            }
            run.nuri.getagrip.ui.components.SecondaryButton(
                title = tr("Share image"),
                icon = Icons.Outlined.Share,
                modifier = Modifier.fillMaxWidth(),
                enabled = !exportSubmission.isRunning,
            ) {
                exportSubmission.launch(scope) {
                    val png = capture(layer)
                    failure = when {
                        png == null -> context.tr(COULD_NOT_RENDER)
                        shareImage(context, png) -> null
                        else -> context.tr("Sharing an image isn't available on this build.")
                    }
                }
            }

            failure?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = palette.alarm)
            }

            Text(
                tr(
                    "Saved at %d × %d points at %d×, so it lands in Photos at the size a story wants.",
                    ShareCalendarGrid.EXPORT_SIDE.toInt(),
                    ShareCalendarGrid.EXPORT_SIDE.toInt(),
                    EXPORT_DENSITY.toInt(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkTertiary,
            )
        }
    }
}

/// The KEY, not the sentence — a `const val` is folded at compile time and could not
/// carry a translation. It is resolved at every use site through `L10n.tr`.
private const val COULD_NOT_RENDER = "Couldn't render the card. Try again."

// MARK: - The card

/// The transparent export shown over a deliberately image-like tonal field, scaled as ONE
/// unit so it never clips on a narrow phone.
///
/// **This is the same composable the export captures.** The preview and the file cannot
/// drift, because there is only one drawing: the card is laid out at its natural 360 dp
/// under a FORCED density of 3 (so it measures exactly 1080 px on every device, whatever
/// the screen's own density is), recorded into a `GraphicsLayer` at that size, and only
/// then scaled down by `graphicsLayer` to fit the box you are looking at.
@Composable
private fun CardPreview(
    request: ShareCalendarRequest,
    style: ShareCardStyle,
    includeBestPull: Boolean,
    layer: androidx.compose.ui.graphics.layer.GraphicsLayer,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(Metrics.radiusInner))
            .drawBehind {
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF9E9E9E), Color(0xFF292929)),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    ),
                )
            }
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(Metrics.radiusInner)),
        contentAlignment = Alignment.Center,
    ) {
        val boxSide = maxWidth
        val deviceDensity = LocalDensity.current
        // The card is 360 dp wide under density 3 → 1080 px. The visible box is `boxSide`
        // dp under the DEVICE's density. The scale is the ratio of the two in pixels.
        val exportPx = ShareCalendarGrid.EXPORT_SIDE * EXPORT_DENSITY
        val boxPx = with(deviceDensity) { boxSide.toPx() }
        val scale = boxPx / exportPx

        CompositionLocalProvider(
            LocalDensity provides Density(EXPORT_DENSITY, deviceDensity.fontScale),
        ) {
            Box(
                Modifier
                    // `requiredSize` ignores the parent's constraints, so the card keeps its
                    // natural export size and only the layer transform shrinks it.
                    .requiredSize(ShareCalendarGrid.EXPORT_SIDE.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin.Center
                    }
                    // Recorded INSIDE the scaling layer, so what lands in the file is the
                    // unscaled 1080 px drawing rather than the thumbnail on screen.
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    },
            ) {
                ShareCalendarExportCard(request, style, includeBestPull)
            }
        }
    }
}

/// The alpha-preserving asset itself, and the ONE drawing the sheet has.
@Composable
private fun ShareCalendarExportCard(
    request: ShareCalendarRequest,
    style: ShareCardStyle,
    includeBestPull: Boolean,
) {
    val ink = style.ink
    val bestPull = if (includeBestPull) request.bestPull else null
    val summary = ShareCalendarGrid.summary(request.days, request.ledger.trackingSince) {
        request.ledger.fraction(it) > 0
    }

    Box(
        Modifier
            .size(ShareCalendarGrid.EXPORT_SIDE.dp)
            .then(style.panelModifier())
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(
                    request.title, summary, bestPull?.line,
                ).joinToString(". ")
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                // Width pinned to the GRID's exact span — 7 fixed cells plus 6 gaps — and
                // centred, so the title, the summary, the best-pull line and the wordmark
                // all share the grid's edges. Text aligned to the card's padding instead sat
                // ~13 pt left of the first column and read as pushed into the corner (Nuri,
                // 2026-08-12).
                .width(ShareCalendarGrid.GRID_WIDTH.dp)
                .padding(vertical = 20.dp)
                .fillMaxSize(),
        ) {
            Text(
                request.title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                color = ink,
                maxLines = 1,
            )
            Spacer(Modifier.height(12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(ShareCalendarGrid.GAP.dp)) {
                request.days.chunked(ShareCalendarGrid.COLUMNS).forEach { week ->
                    Row(horizontalArrangement = Arrangement.spacedBy(ShareCalendarGrid.GAP.dp)) {
                        week.forEach { day ->
                            ExportDayCell(
                                fraction = request.ledger.fraction(day),
                                isToday = day == request.today,
                                isTracked = day >= request.ledger.trackingSince,
                                climbed = request.ledger.climbed(day),
                                benchmarked = request.ledger.benchmarked(day),
                                ink = ink,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = ink,
            )

            if (bestPull != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        bestPull.line,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = ink,
                        maxLines = 1,
                    )
                    ExportHandMark(bestPull.grip.fingers, ink)
                }
            }

            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    tr("GET A GRIP"),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    color = ink.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/// The source grid's three zero-fraction states and rising-fill language, redrawn in the
/// selected ink. The climb notch wins over the benchmark bore, exactly as in History.
@Composable
private fun ExportDayCell(
    fraction: Double,
    isToday: Boolean,
    isTracked: Boolean,
    climbed: Boolean,
    benchmarked: Boolean,
    ink: Color,
) {
    val showsBenchmark = benchmarked && !climbed
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.size(ShareCalendarGrid.CELL.dp), contentAlignment = Alignment.Center) {
        if (isTracked) {
            Box(Modifier.fillMaxSize().background(ink.copy(alpha = 0.18f), shape))
        } else {
            Box(Modifier.width(18.dp).height(2.dp).background(ink.copy(alpha = 0.35f)))
        }
        if (fraction > 0) {
            Box(
                Modifier
                    .fillMaxSize()
                    // A same-ink ring needs a low-opacity moat around the solid fill to
                    // remain a ring; History's grid gets that separation from colour.
                    .padding(if (isToday) 4.dp else 0.dp)
                    .climbNotch(climbed)
                    .benchmarkBore(showsBenchmark, 9.dp)
                    .drawBehind {
                        val height = (size.height * fraction.coerceIn(0.0, 1.0)).toFloat()
                        val r = 6.dp.toPx()
                        drawRoundRect(
                            color = ink,
                            topLeft = Offset(0f, size.height - height),
                            size = Size(size.width, height),
                            cornerRadius = CornerRadius(r, r),
                        )
                    },
            )
        }
        if (isToday) Box(Modifier.fillMaxSize().border(2.dp, ink, shape))
    }
}

/// Export-local version of the app's hand mark: selected fingers in solid ink, excluded ones
/// as capsule outlines, so the grip survives monochrome. Local rather than `HandMark`
/// because that one takes its off-finger step from a tint alpha tuned for the app's palette,
/// and this card has exactly one ink.
@Composable
private fun ExportHandMark(fingers: FingerSet, ink: Color) {
    val bar = 5.dp
    val gap = 2.5.dp
    val length = bar * 1.75f
    val factors = listOf(0.86f, 1.0f, 0.94f, 0.80f)
    Column(verticalArrangement = Arrangement.spacedBy(1.5.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Bottom) {
            fingers.occupied.forEachIndexed { index, on ->
                val shape = RoundedCornerShape(bar / 2)
                Box(
                    Modifier
                        .width(bar)
                        .height(length * factors[index])
                        .then(
                            if (on) Modifier.background(ink, shape)
                            else Modifier.border(1.dp, ink.copy(alpha = 0.45f), shape),
                        ),
                )
            }
        }
        if (fingers.hasThumb) {
            Box(
                Modifier
                    .width(bar * 2 + gap)
                    .height(bar * 0.62f)
                    .background(ink, RoundedCornerShape(bar / 2)),
            )
        }
    }
}

// MARK: - Style

private val ShareCardStyle.ink: Color
    get() = when (this) {
        ShareCardStyle.white -> Color.White
        ShareCardStyle.dark -> Color(0xFF1B1F25)
        ShareCardStyle.frosted -> Color(0xFF1B1F25)
    }

/// The frosted panel is TRANSLUCENCY, not blur: a flat PNG cannot blur what is behind it,
/// and a Material would render grey when captured.
private fun ShareCardStyle.panelModifier(): Modifier = when (this) {
    ShareCardStyle.frosted -> Modifier
        .padding(10.dp)
        .background(Color.White.copy(alpha = 0.72f), RoundedCornerShape(28.dp))
        .border(1.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(28.dp))
    else -> Modifier
}

// MARK: - Geometry

/// The card's geometry, as pure numbers — pinned by `ShareCalendarGridTests`, because the
/// content column's width is DERIVED from the grid rather than guessed, and a card whose
/// text no longer lines up with its first column is exactly the failure that produced the
/// rule.
object ShareCalendarGrid {
    /// 360 × 360, the square every story format crops from without thinking.
    const val EXPORT_SIDE: Float = 360f
    const val COLUMNS: Int = 7
    const val ROWS: Int = 5
    const val CELL: Float = 36f
    const val GAP: Float = 7f

    /// The grid's exact span — 7 fixed cells plus 6 gaps. The WHOLE content column is
    /// constrained to this and centred.
    const val GRID_WIDTH: Float = COLUMNS * CELL + (COLUMNS - 1) * GAP

    /// Exactly the window `HistoryWindows` produces, so a card can never be handed a day
    /// count its grid cannot hold.
    const val CELL_COUNT: Int = COLUMNS * ROWS

    /// The one line of copy under the grid. Counted against the days that were actually
    /// TRACKED, so a first week with the app doesn't read as 6 of 35.
    fun summary(days: List<DayStamp>, trackingSince: DayStamp, trained: (DayStamp) -> Boolean): String {
        val tracked = days.filter { it >= trackingSince }
        return L10n.tr("%d of %d days trained", tracked.count(trained), tracked.size)
    }
}

/// The capture density. Fixed at 3 rather than read from the screen, so the file is the same
/// 1080 × 1080 whether it was made on a cheap phone or a flagship — a shared picture whose
/// resolution depends on which device made it is a picture somebody has to check.
private const val EXPORT_DENSITY: Float = 3f

// MARK: - Delivery

private suspend fun capture(layer: androidx.compose.ui.graphics.layer.GraphicsLayer): ByteArray? =
    try {
        // Graphics capture belongs to the UI context; encoding a 1080px image does not.
        encodeCalendarPng(layer.toImageBitmap().asAndroidBitmap())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

internal suspend fun encodeCalendarPng(bitmap: Bitmap): ByteArray? = withContext(Dispatchers.Default) {
    java.io.ByteArrayOutputStream().use { out ->
        if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
    }
}

/// MediaStore writes can take an arbitrary amount of time on external storage.
private suspend fun saveToPhotos(context: Context, png: ByteArray, title: String): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "get-a-grip-${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Get a Grip")
                put(MediaStore.Images.Media.DESCRIPTION, title)
            }
            val uri = context.contentResolver
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
            context.contentResolver.openOutputStream(uri)?.use { it.write(png) } ?: return@withContext false
            true
        }.getOrDefault(false)
    }

/// Share-cache writes run off main; the chooser still opens on the caller's UI context.
private suspend fun shareImage(context: Context, png: ByteArray): Boolean = try {
    val uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val file = File(dir, "get-a-grip-5-weeks.png")
        file.writeBytes(png)
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
    true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

private const val PREFS = "getagrip.share"
private const val KEY_STYLE = "shareCardStyle"

/// Both guarded: a preview's inspection context has no real preferences behind it, and a
/// remembered style is a convenience — never a reason for the sheet not to open.
private fun loadCardStyle(context: Context): ShareCardStyle = runCatching {
    ShareCardStyle.fromRaw(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STYLE, null),
    )
}.getOrDefault(ShareCardStyle.white)

private fun saveCardStyle(context: Context, style: ShareCardStyle) {
    runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_STYLE, style.rawValue).apply()
    }
}

@Preview(name = "ShareCalendarSheet", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun ShareCalendarSheetPreview() {
    PreviewWorld { feed ->
        val today = LocalDayClock.current.today
        val days = HistoryWindows.days(0, today)
        ShareCalendarSheet(
            ShareCalendarRequest(
                days = days,
                ledger = DayLedger(feed.logs, today),
                title = tr("Last 5 weeks"),
                today = today,
                bestPull = ShareCalendarBestPull(31.0, GripSpec(), Side.left, Instant.now()),
            ),
            onClose = {},
        )
    }
}
