@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.arcu.attnd.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arcu.attnd.AttndApplication
import com.arcu.attnd.domain.AboutText
import com.arcu.attnd.domain.ContextFacts
import com.arcu.attnd.domain.DetailColumns
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventLocation
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.domain.Geo
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.Glance
import com.arcu.attnd.domain.LayoutScreen
import com.arcu.attnd.domain.LayoutSection
import com.arcu.attnd.domain.PlaceWalkMap
import com.arcu.attnd.domain.RelativeTime
import com.arcu.attnd.domain.ScheduleGrouping
import com.arcu.attnd.domain.ScreenFacts
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.Tags
import com.arcu.attnd.domain.WalkRoute
import com.arcu.attnd.domain.Walking
import com.arcu.attnd.ui.assistant.AssistantIntents
import com.arcu.attnd.ui.assistant.MiniMap
import com.arcu.attnd.ui.assistant.MiniPin
import com.arcu.attnd.ui.components.DistanceArrow
import com.arcu.attnd.ui.components.HeartButton
import com.arcu.attnd.ui.components.LiveDot
import com.arcu.attnd.ui.components.LocalEventActions
import com.arcu.attnd.ui.components.LocalScreenshotMode
import com.arcu.attnd.ui.components.RemoteImage
import com.arcu.attnd.ui.components.WalkTime
import com.arcu.attnd.ui.components.rememberDistanceUnits
import com.arcu.attnd.ui.components.rememberNow
import com.arcu.attnd.ui.components.rememberSections
import com.arcu.attnd.ui.components.rememberUserLocation
import com.arcu.attnd.ui.sections.FactsSection
import com.arcu.attnd.ui.sections.HoursSection
import com.arcu.attnd.ui.sections.LinksSection
import com.arcu.attnd.ui.sections.MenuSection
import com.arcu.attnd.ui.sections.PhotoCreditLine
import com.arcu.attnd.ui.sections.SectionCard
import com.arcu.attnd.ui.sections.SectionTitle
import com.arcu.attnd.ui.sections.TextSection
import java.time.Instant

/**
 * A place's detail (iOS `LocationDetailView`), drawn from the `place_detail` layout (API-024; the built-in
 * one = today's screen): the header (photo with its credit, category, name + heart, address, distance,
 * now / next, tags, Show on map), the walking map, directions, now / next, about, hours, good to know,
 * the menu, links (+ the place's website) and what's on here. Two columns from a 700 dp container
 * ([DetailColumns]); one readable column otherwise.
 */
@Composable
fun LocationDetail(
    bundle: EventBundle,
    location: EventLocation,
    onBack: (() -> Unit)?,
    onScheduleItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = rememberNow()
    val sections = rememberSections(LayoutScreen.PLACE_DETAIL, bundle, now, ScreenFacts.subject(location)) { b, state ->
        ScreenFacts.place(location, b, state)
    }
    Column(modifier.fillMaxSize()) {
        if (onBack != null) {
            TopAppBar(
                title = { Text(location.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        }
        DetailSections(sections, LayoutScreen.PLACE_DETAIL) { section ->
            PlaceSection(section, bundle, location, now, onScheduleItemClick)
        }
    }
}

/**
 * A detail screen's sections (iOS `DetailColumns`): from a 700 dp container with enough to fill them, two equal
 * columns from the same top, 48 dp apart; otherwise one readable column, centred and at most 680 dp wide on a wide
 * window. Each column scrolls on its own; this container decides, never the device (a detail sits in a
 * list-detail scaffold's pane, which already keeps it off the hinge).
 */
@Composable
fun DetailSections(sections: List<LayoutSection>, screen: LayoutScreen, section: @Composable (LayoutSection) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth.value.toDouble()
        val split = DetailColumns.split(sections, screen, width)
        if (split == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .wrapContentWidth(Alignment.CenterHorizontally)
                    .widthIn(max = if (width >= DetailColumns.MIN_WIDTH) READABLE_WIDTH else Dp.Unspecified)
                    .padding(bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { sections.forEach { section(it) } }
        } else {
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                listOf(split.leading, split.trailing).forEach { column ->
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { column.forEach { section(it) } }
                }
            }
        }
    }
}

/** One readable column on a wide window (iOS 680 pt). */
private val READABLE_WIDTH = 680.dp

private val SectionPadding = Modifier.padding(horizontal = 16.dp)

@Composable
private fun PlaceSection(section: LayoutSection, bundle: EventBundle, location: EventLocation, now: Instant, onScheduleItemClick: (Int) -> Unit) {
    val context = location.context
    when (section.type) {
        "header" -> PlaceHeader(section, bundle, location, now)
        "walk_map" -> PlaceWalkCard(location, SectionPadding)
        "directions" -> location.coordinate?.let { DirectionsButton(it, SectionPadding) }
        "now_next" -> NowNextLine(bundle, location, now, SectionPadding)
        "about" -> TextSection(section.title ?: "About", AboutText.merged(location.description, context?.about), SectionPadding)
        "hours" -> HoursSection(context?.hours.orEmpty(), SectionPadding, title = section.title)
        "good_to_know" -> FactsSection(ContextFacts.place(context), SectionPadding, title = section.title)
        "menu" -> MenuSection(context?.menu.orEmpty(), SectionPadding, title = section.title, limit = section.limit)
        "links" -> LinksSection(context?.links.orEmpty(), SectionPadding, website = location.website, title = section.title)
        "at_this_location" -> AtThisLocation(section, bundle, location, now, onScheduleItemClick)
        // `ask_about` draws nothing since Nov 21, 2026; unknown types are skipped.
        else -> Unit
    }
}

@Composable
private fun PlaceHeader(section: LayoutSection, bundle: EventBundle, location: EventLocation, now: Instant) {
    val actions = LocalEventActions.current
    if (section.shows("image") && bundle.display.showLocationImages && location.imageUrl != null) {
        Box {
            RemoteImage(url = location.imageUrl, contentDescription = null, modifier = Modifier.fillMaxWidth().height(220.dp))
            PhotoCreditLine(location.imageCredit, location.imageAttributionUrl, Modifier.align(Alignment.BottomEnd))
        }
    }
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (section.shows("category")) location.category?.let { AssistChip(onClick = {}, label = { Text(it) }) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(location.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            HeartButton(SearchKind.LOCATION, location.id, name = location.name)
        }
        if (section.shows("address") && location.address.isNotBlank()) {
            Text(location.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (section.shows("distance") && location.coordinate != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DistanceArrow(location.coordinate, style = MaterialTheme.typography.labelLarge)
                WalkTime(location.coordinate)
            }
        }
        if (section.shows("now_next")) NowNextLine(bundle, location, now)
        if (section.shows("tags") && location.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                location.tags.forEach { tag -> SuggestionChip(onClick = {}, label = { Text(Tags.display(tag)) }) }
            }
        }
        val showOnMap = actions.onShowOnMap?.takeIf { location.coordinate != null }
        if (showOnMap != null) {
            OutlinedButton(onClick = { showOnMap(location.id) }) {
                Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Show on map")
            }
        }
    }
}

/** "Now: X · Next 8:30 Y" at a place with a schedule. */
@Composable
internal fun NowNextLine(bundle: EventBundle, location: EventLocation, now: Instant, modifier: Modifier = Modifier) {
    val line = remember(bundle.schedule, location.id, now) { Glance.locationLine(bundle, location.id, now) } ?: return
    val live = remember(bundle.schedule, location.id, now) { Glance.isLive(bundle, location.id, now) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (live) LiveDot()
        Text(
            line,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DirectionsButton(point: GeoPoint, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Button(onClick = { AssistantIntents.directions(context, point) }, modifier = modifier) {
        Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Get walking directions")
    }
}

/**
 * The small walking map (iOS `PlaceWalkMapCard`): within walking range of a position (not "here", not
 * in screenshot mode) a lite map with the route or the dashed estimate, "12 min walk · 0.6 mi" and
 * Directions (Google Maps; the app never navigates); otherwise the plain Directions button.
 */
@Composable
private fun PlaceWalkCard(location: EventLocation, modifier: Modifier = Modifier) {
    val destination = location.coordinate ?: return
    val screenshot = LocalScreenshotMode.current
    val user = if (screenshot) null else rememberUserLocation()
    val meters = user?.let { Geo.distanceMeters(it, destination) }
    if (!PlaceWalkMap.shows(meters, screenshot)) {
        DirectionsButton(destination, modifier)
        return
    }
    val context = LocalContext.current
    val actions = LocalEventActions.current
    val units = rememberDistanceUnits()
    val router = remember { (context.applicationContext as? AttndApplication)?.graph?.walkRouter }
    var origin by remember { mutableStateOf<GeoPoint?>(null) }
    LaunchedEffect(user) { if (user != null && Walking.shouldReroute(origin, user)) origin = user }
    val route by produceState<WalkRoute?>(null, origin, location.id) {
        val from = origin ?: return@produceState
        value = Walking.estimate(from, destination)
        router?.let { value = it.route(location.id, from, destination) }
    }
    SectionCard(modifier) {
        SectionTitle("Walk there")
        val shown = route
        MiniMap(
            pins = listOf(MiniPin(null, location.id, destination, location.name)),
            legs = listOfNotNull(shown),
            onClick = { actions.onShowOnMap?.invoke(location.id) },
            height = 160.dp,
        )
        if (shown != null) Text(Walking.cardText(shown, units), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { AssistantIntents.directions(context, destination) }) {
            Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Directions")
        }
    }
}

/** What's on at the place, in start order (relative times in the event's zone). */
@Composable
private fun AtThisLocation(section: LayoutSection, bundle: EventBundle, location: EventLocation, now: Instant, onScheduleItemClick: (Int) -> Unit) {
    val zone = bundle.event.zone
    val items = remember(bundle.schedule, location.id) { ScheduleGrouping.sortChronologically(bundle.scheduleAt(location.id)) }
    if (items.isEmpty()) return
    SectionCard(SectionPadding) {
        SectionTitle(section.title ?: "Schedule")
        items.take(section.limit ?: items.size).forEach { item ->
            Column(Modifier.fillMaxWidth().clickable { onScheduleItemClick(item.id) }.padding(vertical = 6.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(EventTime.formatDayTime(item.startTime, zone), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    RelativeTime.forItem(item, now, zone)?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
                Text(item.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
