package com.arcu.attnd.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arcu.attnd.domain.AboutText
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.LayoutScreen
import com.arcu.attnd.domain.LayoutSection
import com.arcu.attnd.domain.ScreenFacts
import com.arcu.attnd.ui.components.EventInfoCard
import com.arcu.attnd.ui.components.EventWebsiteButton
import com.arcu.attnd.ui.components.rememberNow
import com.arcu.attnd.ui.components.rememberSections
import com.arcu.attnd.ui.sections.AmenitiesSection
import com.arcu.attnd.ui.sections.FaqSection
import com.arcu.attnd.ui.sections.LinksSection
import com.arcu.attnd.ui.sections.TextSection

/**
 * The event's info (iOS `EventInfoView`, from Options' "About this event" and search's event result),
 * drawn from the `event_info` layout (API-024; built-in = header, about, FAQ, amenities, getting there,
 * accessibility, policies, weather plan, links; API-018 context).
 */
@Composable
fun EventInfoScreen(bundle: EventBundle, onLocation: (Int) -> Unit, modifier: Modifier = Modifier) {
    val now = rememberNow()
    val sections = rememberSections(LayoutScreen.EVENT_INFO, bundle, now) { b, state -> ScreenFacts.event(b, state) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        sections.forEach { section -> EventInfoSection(section, bundle, onLocation) }
    }
}

@Composable
private fun EventInfoSection(section: LayoutSection, bundle: EventBundle, onLocation: (Int) -> Unit) {
    val event = bundle.event
    val context = event.context
    when (section.type) {
        // The about text is its own section here, so the header leaves the description out.
        "header" -> EventInfoCard(event, showWebsite = section.shows("website"), showDescription = false)
        "about" -> TextSection(section.title ?: "About", AboutText.merged(event.description, context?.about))
        "faq" -> FaqSection(context?.faq.orEmpty(), title = section.title, limit = section.limit)
        "amenities" -> AmenitiesSection(context?.amenities.orEmpty(), bundle, onLocation, title = section.title, limit = section.limit)
        "getting_there" -> TextSection(section.title ?: "Getting There", context?.gettingThere.orEmpty())
        "accessibility" -> TextSection(section.title ?: "Accessibility", context?.accessibility.orEmpty())
        "policies" -> TextSection(section.title ?: "Policies", context?.policies.orEmpty())
        "weather_plan" -> TextSection(section.title ?: "Weather Plan", context?.weatherPlan.orEmpty())
        "links" -> LinksSection(context?.links.orEmpty(), title = section.title)
        else -> Unit
    }
}

/**
 * Options' event rows (iOS `OptionsEventRows`), from the `options_event` layout: the event header, its
 * website (API-016) and "About this event" ([onEventInfo], shown when the event has API-018 context).
 */
@Composable
fun OptionsEventRows(bundle: EventBundle, onEventInfo: () -> Unit) {
    val now = rememberNow()
    val sections = rememberSections(LayoutScreen.OPTIONS_EVENT, bundle, now) { b, state -> ScreenFacts.event(b, state) }
    sections.forEach { section ->
        when (section.type) {
            "header" -> EventInfoCard(bundle.event, showWebsite = false)
            "website" -> bundle.event.websiteUrl?.let { EventWebsiteButton(it) }
            "event_info_link" -> ListItem(
                modifier = Modifier.clickable(onClick = onEventInfo),
                leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                supportingContent = { Text("FAQ, getting there, amenities and more") },
            ) { Text("About this event") }
            else -> Unit
        }
    }
}
