package com.arcu.attnd.ui.sections

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Accessible
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Atm
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.arcu.attnd.data.PhotoAttributionLoader
import com.arcu.attnd.domain.Amenity
import com.arcu.attnd.domain.AmenityKind
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.ContextFact
import com.arcu.attnd.domain.ContextLink
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.FaqEntry
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.MenuEntry
import com.arcu.attnd.domain.Performer
import com.arcu.attnd.domain.Tags
import com.arcu.attnd.ui.theme.themedElevatedCardColors

/*
 * API-018 rich context on the detail screens, Home and chat answers (iOS `ContextViews.swift` and
 * `ScreenComponentView`'s pieces): one composable per layout section, all from the bundle on disk (so
 * offline too). Links open in the browser (`ACTION_VIEW` + `CATEGORY_BROWSABLE`), https only; nothing
 * from the app goes to them.
 */

/** Opens an https link in the browser (or the app that claims it, such as Apple Music). */
fun openLink(context: Context, url: String, where: String = "context_link") {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        AppLog.caught(LogCategory.UI, where, e)
    }
}

/** A section's card (iOS `blockCardBackground`): the event theme's soft wash, else the elevated default. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = themedElevatedCardColors(), elevation = CardDefaults.elevatedCardElevation(), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** A section's title (a heading for TalkBack). */
@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = modifier.semantics { heading() },
    )
}

/** One of the text parts (About, Getting there, Accessibility, Policies, Weather plan, Hours). Nothing when empty. */
@Composable
fun TextSection(title: String, text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    SectionCard(modifier) {
        SectionTitle(title)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** An item's performers with their genres, bio and the API-030 Apple Music button. */
@Composable
fun PerformersSection(performers: List<Performer>, modifier: Modifier = Modifier, title: String? = null, bioLines: Int = Int.MAX_VALUE) {
    if (performers.isEmpty()) return
    SectionCard(modifier) {
        SectionTitle(title ?: if (performers.size == 1) "Performer" else "Performers")
        performers.forEach { performer ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                Text(performer.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (performer.genres.isNotEmpty()) {
                    Text(performer.genres.joinToString(" · ") { Tags.display(it) }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (performer.bio.isNotEmpty()) {
                    Text(
                        performer.bio,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = bioLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                performer.appleMusicUrl?.let { url ->
                    ListenOnAppleMusicButton(url, Modifier.padding(top = 2.dp), artist = performer.name)
                }
            }
        }
    }
}

/**
 * API-030 "Listen on Apple Music" (Apple's wording): opens the artist page with `ACTION_VIEW` (the Apple
 * Music app when it claims the link, else the browser). No account, no SDK; nothing without a link.
 */
@Composable
fun ListenOnAppleMusicButton(url: String, modifier: Modifier = Modifier, artist: String = "") {
    val context = LocalContext.current
    val label = if (artist.isEmpty()) "Listen on Apple Music" else "Listen to $artist on Apple Music"
    OutlinedButton(
        onClick = { openLink(context, url, "apple_music") },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        modifier = modifier.semantics { contentDescription = label },
    ) {
        Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Listen on Apple Music", style = MaterialTheme.typography.labelLarge)
    }
}

/** "Good to know": labelled lines (price, ages, accessibility). */
@Composable
fun FactsSection(facts: List<ContextFact>, modifier: Modifier = Modifier, title: String? = null) {
    if (facts.isEmpty()) return
    SectionCard(modifier) {
        SectionTitle(title ?: "Good to Know")
        facts.forEach { fact ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(fact.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(112.dp))
                Text(fact.value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** The organizer's links, then (places) the place's own website as "Visit Website". */
@Composable
fun LinksSection(links: List<ContextLink>, modifier: Modifier = Modifier, website: String? = null, title: String? = null) {
    if (links.isEmpty() && website == null) return
    val context = LocalContext.current
    SectionCard(modifier) {
        SectionTitle(title ?: "Links")
        links.forEach { link -> LinkRow(link.title, onClick = { openLink(context, link.url) }) }
        website?.let { url -> LinkRow("Visit Website", onClick = { openLink(context, url, "place_website") }) }
    }
}

@Composable
private fun LinkRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** A place's opening hours ("Hours"). */
@Composable
fun HoursSection(hours: String, modifier: Modifier = Modifier, title: String? = null) {
    if (hours.isBlank()) return
    SectionCard(modifier) {
        SectionTitle(title ?: "Hours")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(hours, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** A place's menu: name, note, price. */
@Composable
fun MenuSection(menu: List<MenuEntry>, modifier: Modifier = Modifier, title: String? = null, limit: Int? = null) {
    if (menu.isEmpty()) return
    SectionCard(modifier) {
        SectionTitle(title ?: "Menu")
        menu.take(limit ?: menu.size).forEach { entry ->
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                    if (entry.note.isNotEmpty()) {
                        Text(entry.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (entry.price.isNotEmpty()) {
                    Text(entry.price, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** The event's FAQ as expandable questions; with more than [limit], "All questions" ([onAll]). */
@Composable
fun FaqSection(faq: List<FaqEntry>, modifier: Modifier = Modifier, title: String? = null, limit: Int? = null, onAll: (() -> Unit)? = null) {
    if (faq.isEmpty()) return
    val shown = faq.take(limit ?: faq.size)
    SectionCard(modifier) {
        SectionTitle(title ?: "FAQ")
        shown.forEachIndexed { index, entry -> FaqRow(entry, key = "faq-$index-${entry.question}") }
        if (onAll != null && faq.size > shown.size) {
            TextButton(onClick = onAll, contentPadding = PaddingValues(0.dp)) { Text("All questions") }
        }
    }
}

@Composable
private fun FaqRow(entry: FaqEntry, key: String) {
    var open by rememberSaveable(key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "Hide the answer" else "Show the answer") { open = !open }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(entry.question, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
        if (open) {
            Text(entry.answer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

/** The event's amenities with the place they are at (tap → that place). */
@Composable
fun AmenitiesSection(
    amenities: List<Amenity>,
    bundle: EventBundle,
    onLocation: (Int) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    limit: Int? = null,
) {
    if (amenities.isEmpty()) return
    SectionCard(modifier) {
        SectionTitle(title ?: "Amenities")
        amenities.take(limit ?: amenities.size).forEach { amenity ->
            val place = bundle.location(amenity.locationId)
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (place != null) Modifier.clickable(onClickLabel = "Open ${place.name}") { onLocation(place.id) } else Modifier)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(amenity.kind.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(amenity.name, style = MaterialTheme.typography.bodyLarge)
                    val detail = listOfNotNull(place?.name, amenity.note.takeIf { it.isNotEmpty() }).joinToString(" · ")
                    if (detail.isNotEmpty()) {
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private val AmenityKind.icon: ImageVector
    get() = when (this) {
        AmenityKind.RESTROOMS -> Icons.Filled.Wc
        AmenityKind.WATER -> Icons.Filled.WaterDrop
        AmenityKind.ATM -> Icons.Filled.Atm
        AmenityKind.FIRST_AID -> Icons.Filled.LocalHospital
        AmenityKind.CHARGING -> Icons.Filled.BatteryChargingFull
        AmenityKind.LOCKERS -> Icons.Filled.Lock
        AmenityKind.INFO -> Icons.Filled.Info
        AmenityKind.ACCESSIBLE_ENTRANCE -> Icons.AutoMirrored.Filled.Accessible
        AmenityKind.FOOD -> Icons.Filled.Restaurant
        AmenityKind.MERCH -> Icons.Filled.ShoppingBag
        AmenityKind.PARKING -> Icons.Filled.LocalParking
        AmenityKind.SHUTTLE -> Icons.Filled.DirectionsBus
        AmenityKind.OTHER -> Icons.Filled.Star
    }

/**
 * API-022 / API-031: the small credit over a large place photo's bottom-end corner, on a dark scrim:
 * `image_credit`, or for a live Google photo the authors from `image_attribution_url` (loaded in memory
 * only), each name linked to their profile. Nothing without a credit.
 */
@Composable
fun PhotoCreditLine(credit: String?, attributionUrl: String?, modifier: Modifier = Modifier) {
    var attribution by remember(attributionUrl) { mutableStateOf(attributionUrl?.let(PhotoAttributionLoader::cached)) }
    LaunchedEffect(attributionUrl) {
        if (attributionUrl != null && attribution == null) attribution = PhotoAttributionLoader.load(attributionUrl)
    }
    val text = attribution?.let { a ->
        buildAnnotatedString {
            append(a.credit)
            val style = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))
            a.links.forEach { (start, end, uri) -> addLink(LinkAnnotation.Url(uri, style), start, end) }
        }
    } ?: credit?.takeIf { it.isNotBlank() }?.let { buildAnnotatedString { append(it) } } ?: return
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(8.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
