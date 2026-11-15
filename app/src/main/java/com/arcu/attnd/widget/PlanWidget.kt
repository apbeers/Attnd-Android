package com.arcu.attnd.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.arcu.attnd.AttndApplication
import com.arcu.attnd.assistant.OpenIntents
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogLevel
import com.arcu.attnd.domain.OpenRequest
import com.arcu.attnd.domain.OpenTarget
import com.arcu.attnd.domain.PlanWidgetContent
import kotlinx.coroutines.CancellationException
import java.time.Instant

/**
 * The home-screen widget "Now / Next in my plan" (assistant spec §6; Jetpack Glance): now, next and
 * the walk between, from the bundle and plan on the device ([PlanWidgetContent]). Tapping opens the
 * item (or My plan). Updated whenever the bundle, likes or plan change (AppGraph) and every 30 min
 * by the system (`res/xml/plan_widget_info.xml`).
 */
class PlanWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = (context.applicationContext as AttndApplication).graph
        val bundle = graph.repository.restore()
        val eventId = bundle?.event?.id
        val content = PlanWidgetContent.of(
            bundle,
            eventId?.let(graph.plans::plan),
            eventId?.let(graph.hearts::hearts) ?: Hearts.NONE,
            Instant.now(),
        )
        val target = content.focusItemId?.let { OpenTarget.ScheduleItem(it) } ?: OpenTarget.MyPlan
        val open = OpenIntents.intent(context, OpenRequest(eventId, target))
        provideContent {
            GlanceTheme {
                Column(
                    GlanceModifier.fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(16.dp)
                        .padding(12.dp)
                        .clickable(actionStartActivity(open)),
                ) {
                    Text(content.title, style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 14.sp), maxLines = 1)
                    Spacer(GlanceModifier.height(4.dp))
                    content.nowLine?.let { Text(it, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 2) }
                    content.nextLine?.let { Text(it, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp), maxLines = 2) }
                    content.walkLine?.let { Text(it, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1) }
                }
            }
        }
    }

    companion object {
        /** Redraws every placed widget (no-op without one). */
        suspend fun refresh(context: Context) {
            try {
                PlanWidget().updateAll(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.caught(LogCategory.UI, "widget.update", e, LogLevel.WARNING)
            }
        }
    }
}

class PlanWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlanWidget()
}
