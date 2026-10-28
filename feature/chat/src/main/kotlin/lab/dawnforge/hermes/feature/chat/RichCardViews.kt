package lab.dawnforge.hermes.feature.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import lab.dawnforge.hermes.core.model.CardSource
import lab.dawnforge.hermes.core.model.ChartCard
import lab.dawnforge.hermes.core.model.ChartKind
import lab.dawnforge.hermes.core.model.ComparisonCard
import lab.dawnforge.hermes.core.model.ContactCard
import lab.dawnforge.hermes.core.model.MediaCard
import lab.dawnforge.hermes.core.model.PlacesCard
import lab.dawnforge.hermes.core.model.RichCard
import lab.dawnforge.hermes.core.model.StatsCard
import lab.dawnforge.hermes.core.model.StatusCard
import lab.dawnforge.hermes.core.model.StatusPhase
import lab.dawnforge.hermes.core.model.StepsCard
import lab.dawnforge.hermes.core.model.Tone
import lab.dawnforge.hermes.core.model.Trend
import lab.dawnforge.hermes.core.model.WeatherCard
import lab.dawnforge.hermes.core.model.WeatherCondition

/** Whether this build draws maps (it has a Google Maps key); the app provides it. */
val LocalMapsAvailable = staticCompositionLocalOf { false }

/**
 * The cards a `talaria_show` call showed, in place of its tool card. [superseded] names the live
 * cards a later card replaced: those collapse to a line saying so.
 */
@Composable
internal fun RichCardsView(cards: List<RichCard>, superseded: Set<String>, modifier: Modifier = Modifier) {
    Column(modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cards.forEach { card ->
            val live = card.live
            if (live != null && live in superseded) {
                Text(
                    stringResource(R.string.feature_chat_card_updated_below, card.title ?: live),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                RichCardView(card)
            }
        }
    }
}

@Composable
private fun RichCardView(card: RichCard) {
    when (card) {
        is PlacesCard -> PlacesCardView(card)
        is StatsCard -> InfoFrame(card.title, card.source) { StatsView(card) }
        is ChartCard -> InfoFrame(card.title, card.source) { ChartView(card) }
        is WeatherCard -> InfoFrame(card.title, card.source) { WeatherView(card) }
        is ComparisonCard -> InfoFrame(card.title, card.source) { ComparisonView(card) }
        is StepsCard -> InfoFrame(card.title, card.source) { StepsView(card) }
        is MediaCard -> InfoFrame(card.title, card.source) { MediaView(card) }
        is ContactCard -> InfoFrame(card.title, card.source) { ContactView(card) }
        is StatusCard -> InfoFrame(card.title, card.source) { StatusView(card) }
    }
}

/** A card's surface. */
@Composable
internal fun CardSurface(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

/** An information card: its title, its content, then where it's from (or that it's Hermes' own). */
@Composable
private fun InfoFrame(title: String?, source: CardSource?, content: @Composable () -> Unit) {
    val context = LocalContext.current
    CardSurface {
        title?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        content()
        val url = source?.url
        Text(
            if (source != null) {
                stringResource(R.string.feature_chat_card_source, source.name)
            } else {
                stringResource(R.string.feature_chat_card_from_hermes)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textDecoration = if (url != null) TextDecoration.Underline else null,
            modifier = if (url != null) Modifier.clickable { HostFiles.browse(context, url) } else Modifier,
        )
    }
}

// ---- Stats ---------------------------------------------------------------------------------

@Composable
private fun StatsView(card: StatsCard) {
    val columns = if (card.stats.size == 1) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        card.stats.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { stat ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            stat.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stat.value,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        if (stat.trend != null || stat.change != null) {
                            val arrow = when (stat.trend) {
                                Trend.Up -> "↗"
                                Trend.Down -> "↘"
                                Trend.Flat -> "→"
                                null -> ""
                            }
                            Text(
                                listOfNotNull(arrow.ifEmpty { null }, stat.change).joinToString(" "),
                                style = MaterialTheme.typography.labelMedium,
                                color = toneColor(stat.tone),
                            )
                        }
                        stat.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (stat.spark.size >= 2) {
                            val color = when (stat.tone) {
                                Tone.Neutral -> MaterialTheme.colorScheme.primary
                                else -> toneColor(stat.tone)
                            }
                            Sparkline(stat.spark, color, Modifier.fillMaxWidth().height(28.dp))
                        }
                    }
                }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun toneColor(tone: Tone): Color = when (tone) {
    Tone.Good -> GOOD
    Tone.Bad -> MaterialTheme.colorScheme.error
    Tone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun Sparkline(values: List<Double>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val low = values.min()
        val span = (values.max() - low).takeIf { it > 0 } ?: 1.0
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, value ->
            val point = Offset(i * step, size.height - ((value - low) / span * size.height).toFloat())
            if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}

// ---- Chart ---------------------------------------------------------------------------------

@Composable
private fun ChartView(card: ChartCard) {
    val palette = chartPalette()
    if (card.kind == ChartKind.Pie && card.series.first().values.sum() > 0) {
        PieView(card, palette)
        return
    }
    val all = card.series.flatMap { it.values }
    val low = minOf(0.0, all.min())
    val high = max(all.max(), low + 1)
    val axis = MaterialTheme.colorScheme.outlineVariant
    card.unit?.let { unit ->
        Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Canvas(Modifier.fillMaxWidth().height(210.dp)) {
        val y = { value: Double -> size.height - ((value - low) / (high - low) * size.height).toFloat() }
        drawLine(axis, Offset(0f, y(0.0)), Offset(size.width, y(0.0)))
        val count = card.labels.size
        val kind = if (card.kind == ChartKind.Pie) ChartKind.Bar else card.kind
        if (kind == ChartKind.Bar) {
            val group = size.width / count
            val bar = group * BAR_FILL / card.series.size
            card.series.forEachIndexed { s, series ->
                series.values.forEachIndexed { i, value ->
                    val left = i * group + group * (1 - BAR_FILL) / 2 + s * bar
                    val top = minOf(y(value), y(0.0))
                    drawRect(
                        palette[s % palette.size],
                        Offset(left, top),
                        Size(bar, kotlin.math.abs(y(value) - y(0.0))),
                    )
                }
            }
        } else {
            val step = if (count > 1) size.width / (count - 1) else 0f
            card.series.forEachIndexed { s, series ->
                val color = palette[s % palette.size]
                val path = Path()
                series.values.forEachIndexed { i, value ->
                    if (i == 0) path.moveTo(i * step, y(value)) else path.lineTo(i * step, y(value))
                }
                if (kind == ChartKind.Area) {
                    val fill = Path().apply {
                        addPath(path)
                        lineTo((series.values.size - 1) * step, y(0.0))
                        lineTo(0f, y(0.0))
                        close()
                    }
                    drawPath(fill, color.copy(alpha = AREA_ALPHA))
                }
                drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
                series.values.forEachIndexed { i, value -> drawCircle(color, 3.dp.toPx(), Offset(i * step, y(value))) }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val every = card.labels.size / MAX_AXIS_LABELS + 1
        val shown = card.labels.filterIndexed { i, _ -> card.labels.size <= MAX_AXIS_LABELS || i % every == 0 }
        shown.forEach { label ->
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (card.series.size > 1) {
        Legend(card.series.mapIndexed { i, series -> Pair(series.name ?: "${i + 1}", palette[i % palette.size]) })
    }
}

@Composable
private fun PieView(card: ChartCard, palette: List<Color>) {
    val values = card.series.first().values
    val total = values.sum()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Canvas(Modifier.size(150.dp)) {
            var start = -90f
            values.forEachIndexed { i, value ->
                val sweep = (value / total * 360).toFloat()
                drawArc(
                    palette[i % palette.size],
                    start,
                    sweep,
                    useCenter = false,
                    style = Stroke(
                        width =
                            size.minDimension * DONUT_WIDTH,
                    ),
                )
                start += sweep
            }
        }
        Legend(
            card.labels.mapIndexed { i, label ->
                "$label · ${(values.getOrElse(i) { 0.0 } / total * 100).roundToInt()}%" to palette[i % palette.size]
            },
        )
    }
}

@Composable
private fun Legend(items: List<Pair<String, Color>>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun chartPalette(): List<Color> = listOf(
    MaterialTheme.colorScheme.primary,
    Color(0xFF34C759),
    Color(0xFFFF9500),
    Color(0xFFAF52DE),
    Color(0xFFFF3B30),
    Color(0xFF30B0C7),
    Color(0xFFFFCC00),
    Color(0xFFFF2D55),
    Color(0xFF5856D6),
    Color(0xFF00C7BE),
    Color(0xFFA2845E),
    Color(0xFF32ADE6),
)

// ---- Weather -------------------------------------------------------------------------------

@Composable
private fun WeatherView(card: WeatherCard) {
    card.now?.let { now ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(conditionSymbol(now.condition), style = MaterialTheme.typography.displaySmall)
            Column {
                card.place?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                Text(
                    "${now.temp.roundToInt()}°${card.unit}",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(stringResource(conditionName(now.condition)), style = MaterialTheme.typography.bodyMedium)
                now.text?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (card.now == null) card.place?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
    if (card.hours.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            card.hours.forEach { hour ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(hour.time, style = MaterialTheme.typography.labelSmall)
                    Text(hour.condition?.let(::conditionSymbol) ?: THERMOMETER)
                    Text("${hour.temp.roundToInt()}°", style = MaterialTheme.typography.labelLarge)
                    hour.precip?.takeIf { it > 0 }?.let { precip ->
                        Text("💧$precip%", style = MaterialTheme.typography.labelSmall, color = PRECIP)
                    }
                }
            }
        }
    }
    if (card.days.isNotEmpty()) {
        val weekLow = card.days.minOf { it.low }
        val weekHigh = card.days.maxOf { it.high }.coerceAtLeast(weekLow + 1)
        card.days.forEach { day ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(day.day, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp))
                Text(day.condition?.let(::conditionSymbol) ?: THERMOMETER, modifier = Modifier.width(28.dp))
                Text(
                    day.precip?.takeIf {
                        it > 0
                    }?.let {
                        "$it%"
                    }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = PRECIP,
                    modifier = Modifier.width(36.dp),
                )
                Text(
                    "${day.low.roundToInt()}°",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val start = ((day.low - weekLow) / (weekHigh - weekLow)).toFloat()
                val end = ((day.high - weekLow) / (weekHigh - weekLow)).toFloat()
                val track = MaterialTheme.colorScheme.outlineVariant
                Canvas(Modifier.weight(1f).height(6.dp)) {
                    drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
                    drawRoundRect(
                        Brush.horizontalGradient(listOf(PRECIP, Color(0xFFFFCC00), Color(0xFFFF9500))),
                        topLeft = Offset(size.width * start, 0f),
                        size = Size(size.width * (end - start).coerceAtLeast(MIN_RANGE), size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
                    )
                }
                Text(
                    "${day.high.roundToInt()}°",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun conditionSymbol(condition: WeatherCondition): String = when (condition) {
    WeatherCondition.Clear -> "☀️"
    WeatherCondition.PartlyCloudy -> "⛅"
    WeatherCondition.Cloudy -> "☁️"
    WeatherCondition.Fog -> "🌫️"
    WeatherCondition.Drizzle -> "🌦️"
    WeatherCondition.Rain -> "🌧️"
    WeatherCondition.HeavyRain -> "🌧️"
    WeatherCondition.Thunderstorm -> "⛈️"
    WeatherCondition.Snow -> "🌨️"
    WeatherCondition.Sleet -> "🌨️"
    WeatherCondition.Wind -> "💨"
}

private fun conditionName(condition: WeatherCondition): Int = when (condition) {
    WeatherCondition.Clear -> R.string.feature_chat_weather_clear
    WeatherCondition.PartlyCloudy -> R.string.feature_chat_weather_partly_cloudy
    WeatherCondition.Cloudy -> R.string.feature_chat_weather_cloudy
    WeatherCondition.Fog -> R.string.feature_chat_weather_fog
    WeatherCondition.Drizzle -> R.string.feature_chat_weather_drizzle
    WeatherCondition.Rain -> R.string.feature_chat_weather_rain
    WeatherCondition.HeavyRain -> R.string.feature_chat_weather_heavy_rain
    WeatherCondition.Thunderstorm -> R.string.feature_chat_weather_thunderstorm
    WeatherCondition.Snow -> R.string.feature_chat_weather_snow
    WeatherCondition.Sleet -> R.string.feature_chat_weather_sleet
    WeatherCondition.Wind -> R.string.feature_chat_weather_wind
}

// ---- Comparison ----------------------------------------------------------------------------

@Composable
private fun ComparisonView(card: ComparisonCard) {
    val width = if (card.options.size == 2) 200.dp else 220.dp
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        card.options.forEach { option ->
            val border = if (option.recommended) MaterialTheme.colorScheme.primary else Color.Transparent
            Column(
                Modifier
                    .width(width)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(2.dp, border, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (option.recommended) {
                    Text(
                        "★ " + stringResource(R.string.feature_chat_card_recommended),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(option.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                option.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                option.facts.forEach { fact ->
                    Row {
                        Text(
                            fact.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(fact.value, style = MaterialTheme.typography.labelMedium)
                    }
                }
                option.pros.forEach { Text("＋ $it", style = MaterialTheme.typography.bodySmall, color = GOOD) }
                option.cons.forEach { Text("－ $it", style = MaterialTheme.typography.bodySmall, color = CON) }
            }
        }
    }
}

// ---- Steps ---------------------------------------------------------------------------------

@Composable
private fun StepsView(card: StepsCard) {
    if (card.needs.isNotEmpty()) {
        Text(
            stringResource(R.string.feature_chat_card_needs).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        card.needs.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
    card.steps.forEachIndexed { index, step ->
        var done by rememberSaveable(card.title, index, step.text) { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    .background(if (done) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { done = !done },
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${index + 1}. ${step.text}",
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    color = when {
                        done -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                step.timerSeconds?.let { StepTimer(it) }
            }
        }
    }
}

/** A step's timer: tap to start, tap again to stop; it says when it's done. */
@Composable
private fun StepTimer(seconds: Int) {
    var endsAt by rememberSaveable { mutableLongStateOf(0L) }
    var now by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    val running = endsAt > 0 && now < endsAt
    val finished = endsAt > 0 && now >= endsAt
    LaunchedEffect(endsAt) {
        while (endsAt > 0 && System.currentTimeMillis() < endsAt) {
            now = System.currentTimeMillis()
            delay(TICK_MS)
        }
        now = System.currentTimeMillis()
    }
    val left = if (running) ((endsAt - now) / MS_PER_S).toInt() + 1 else seconds
    Surface(
        shape = RoundedCornerShape(50),
        color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        modifier = Modifier.clickable {
            endsAt = if (running) 0L else System.currentTimeMillis() + seconds * MS_PER_S
            now = System.currentTimeMillis()
        },
    ) {
        Text(
            when {
                finished -> "🔔 " + stringResource(R.string.feature_chat_card_timer_done)
                running -> "■ " + clock(left)
                else -> "⏱ " + clock(seconds)
            },
            style = MaterialTheme.typography.labelLarge,
            color = if (running) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private fun clock(seconds: Int): String {
    val h = seconds / SECONDS_PER_HOUR
    val m = seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val s = seconds % SECONDS_PER_MINUTE
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// ---- Status --------------------------------------------------------------------------------

@Composable
private fun StatusView(card: StatusCard) {
    val (badge, color) = when (card.phase) {
        StatusPhase.Waiting -> R.string.feature_chat_card_phase_waiting to MaterialTheme.colorScheme.onSurfaceVariant
        StatusPhase.Active -> R.string.feature_chat_card_phase_active to MaterialTheme.colorScheme.primary
        StatusPhase.Done -> R.string.feature_chat_card_phase_done to GOOD
        StatusPhase.Problem -> R.string.feature_chat_card_phase_problem to CON
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(card.state, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = BADGE_ALPHA)) {
            Text(
                stringResource(badge),
                style = MaterialTheme.typography.labelSmall,
                color = color,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
    val progress = if (card.phase == StatusPhase.Done) 1.0 else card.progress
    progress?.let { value ->
        LinearProgressIndicator(progress = { value.toFloat() }, modifier = Modifier.fillMaxWidth(), color = color)
    }
    card.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    card.eta?.let { eta ->
        Text("🕒 $eta", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    card.milestones.forEach { milestone ->
        Text(
            (if (milestone.done) "✓ " else "○ ") + milestone.label,
            style = MaterialTheme.typography.bodySmall,
            color = if (milestone.done) color else MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = if (milestone.done) FontStyle.Normal else FontStyle.Normal,
        )
    }
}

private val GOOD = Color(0xFF34C759)
private val CON = Color(0xFFFF9500)
private val PRECIP = Color(0xFF32ADE6)
private const val THERMOMETER = "🌡️"
private const val BAR_FILL = 0.7f
private const val AREA_ALPHA = 0.35f
private const val DONUT_WIDTH = 0.21f
private const val MAX_AXIS_LABELS = 6
private const val MIN_RANGE = 0.04f
private const val BADGE_ALPHA = 0.15f
private const val TICK_MS = 250L
private const val MS_PER_S = 1000L
private const val SECONDS_PER_HOUR = 3600
private const val SECONDS_PER_MINUTE = 60
