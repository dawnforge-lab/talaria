package lab.dawnforge.hermes.core.model

/**
 * A card Hermes showed with the push plugin's `talaria_show` tool, already checked against the
 * plugin's card schema: a map or places, numbers, a chart, a forecast, a comparison, steps, media,
 * people, or a status. [live] names a card a later one of the same name replaces.
 */
sealed interface RichCard {
    val title: String?
    val live: String?
}

/** Where a card's facts are from, when from a tool, a site or a service rather than Hermes itself. */
data class CardSource(val name: String, val url: String?)

/** A photo and where it's from. */
data class CardPhoto(val url: String, val source: String, val pageUrl: String?)

/** A fact about a place (its hours, its price) with where it's from. */
data class SourcedText(val text: String, val source: String, val url: String?)

data class PlaceRating(val value: Double, val count: Long?, val source: String, val url: String?)

data class CardPlace(
    val name: String,
    val address: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    /** An Apple Maps place id, when Hermes had one. */
    val placeId: String? = null,
    /** Hermes' own note on it. */
    val note: String? = null,
    val rating: PlaceRating? = null,
    val hours: SourcedText? = null,
    val price: SourcedText? = null,
    val photo: CardPhoto? = null,
)

enum class RouteMode { Driving, Walking, Transit, Cycling }

/** Directions between two of the places, or ([from] null) from where the user is. */
data class CardRoute(val to: Int, val from: Int?, val mode: RouteMode)

enum class PlacesKind {
    /** A map with a pin per place, and a route. */
    Map,

    /** One place, in detail. */
    Place,

    /** A strip of places to choose from. */
    Places,
}

data class PlacesCard(
    override val title: String?,
    override val live: String?,
    val kind: PlacesKind,
    val places: List<CardPlace>,
    val route: CardRoute? = null,
) : RichCard

enum class Trend { Up, Down, Flat }

enum class Tone { Good, Bad, Neutral }

data class CardStat(
    val label: String,
    val value: String,
    val detail: String? = null,
    val change: String? = null,
    val trend: Trend? = null,
    val tone: Tone = Tone.Neutral,
    val spark: List<Double> = emptyList(),
)

data class StatsCard(
    override val title: String?,
    override val live: String?,
    val source: CardSource?,
    val stats: List<CardStat>,
) : RichCard

enum class ChartKind { Line, Bar, Area, Pie }

data class ChartSeries(val name: String?, val values: List<Double>)

data class ChartCard(
    override val title: String?,
    override val live: String?,
    val source: CardSource?,
    val kind: ChartKind,
    val labels: List<String>,
    val series: List<ChartSeries>,
    val unit: String? = null,
) : RichCard

enum class WeatherCondition {
    Clear,
    PartlyCloudy,
    Cloudy,
    Fog,
    Drizzle,
    Rain,
    HeavyRain,
    Thunderstorm,
    Snow,
    Sleet,
    Wind,
}

data class WeatherNow(val temp: Double, val condition: WeatherCondition, val text: String? = null)

data class WeatherHour(
    val time: String,
    val temp: Double,
    val condition: WeatherCondition? = null,
    val precip: Int? = null,
)

data class WeatherDay(
    val day: String,
    val low: Double,
    val high: Double,
    val condition: WeatherCondition? = null,
    val precip: Int? = null,
)

data class WeatherCard(
    override val title: String?,
    override val live: String?,
    val source: CardSource,
    /** `C` or `F`. */
    val unit: String,
    val place: String? = null,
    val now: WeatherNow? = null,
    val hours: List<WeatherHour> = emptyList(),
    val days: List<WeatherDay> = emptyList(),
) : RichCard

data class CardFact(val label: String, val value: String)

data class CompareOption(
    val name: String,
    val summary: String? = null,
    val facts: List<CardFact> = emptyList(),
    val pros: List<String> = emptyList(),
    val cons: List<String> = emptyList(),
    val recommended: Boolean = false,
)

data class ComparisonCard(
    override val title: String?,
    override val live: String?,
    val source: CardSource?,
    val options: List<CompareOption>,
) : RichCard

data class CardStep(val text: String, val timerSeconds: Int? = null)

data class StepsCard(
    override val title: String?,
    override val live: String?,
    val source: CardSource?,
    val needs: List<String>,
    val steps: List<CardStep>,
) : RichCard

enum class CardMediaKind { Image, Video, Audio }

data class CardMedia(
    val kind: CardMediaKind,
    val url: String,
    val title: String? = null,
    val by: String? = null,
    val caption: String? = null,
    val durationSeconds: Int? = null,
    val artwork: String? = null,
    val page: String? = null,
)

data class MediaCard(
    override val title: String?,
    override val live: String?,
    val source: CardSource,
    val media: List<CardMedia>,
) : RichCard

data class CardPerson(
    val name: String,
    val detail: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val url: String? = null,
    val address: String? = null,
    val note: String? = null,
    val photo: CardPhoto? = null,
)

data class ContactCard(
    override val title: String?,
    override val live: String?,
    val source: CardSource?,
    val people: List<CardPerson>,
) : RichCard

enum class StatusPhase { Waiting, Active, Done, Problem }

data class Milestone(val label: String, val done: Boolean)

data class StatusCard(
    override val title: String?,
    override val live: String?,
    val source: CardSource?,
    val state: String,
    val phase: StatusPhase = StatusPhase.Active,
    /** 0 to 1. */
    val progress: Double? = null,
    val detail: String? = null,
    val eta: String? = null,
    val milestones: List<Milestone> = emptyList(),
) : RichCard
