@file:OptIn(ExperimentalMaterial3Api::class)

package com.arcu.attnd.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogFilter
import com.arcu.attnd.domain.LogJson
import com.arcu.attnd.domain.LogLevel
import com.arcu.attnd.domain.LogRecord
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the log viewer needs from the app (the ring buffer, the `.jsonl` export, the upload status). */
class LogViewerSource(
    val load: suspend () -> List<LogRecord>,
    val export: suspend (LogFilter?) -> File,
    val uploadStatus: () -> String?,
)

/**
 * The on-device log viewer (logging spec §2): Options → About & support → "Logs" (dev builds and debug
 * mode) or "Recent logs" (production, what the ring buffer kept: warnings and errors). Filters by level,
 * category, text and errors only; the share action sends the filtered lines as a `.jsonl` file.
 */
@Composable
fun LogViewerScreen(source: LogViewerSource, title: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<LogRecord>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var text by rememberSaveable { mutableStateOf("") }
    var minLevel by rememberSaveable { mutableStateOf(LogLevel.TRACE) }
    var errorsOnly by rememberSaveable { mutableStateOf(false) }
    var categories by remember { mutableStateOf(emptySet<LogCategory>()) }
    var shareError by remember { mutableStateOf<String?>(null) }
    val filter = LogFilter(minLevel = minLevel, categories = categories, text = text, errorsOnly = errorsOnly)

    LaunchedEffect(reload) { records = source.load() }
    val shown = remember(records, filter) { filter.apply(records.orEmpty()) }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, contentDescription = "Reload") }
                    IconButton(onClick = {
                        scope.launch {
                            shareError = try {
                                shareLogs(context, source.export(filter))
                                null
                            } catch (e: Exception) {
                                AppLog.caught(LogCategory.LOGGING, "logs.share", e)
                                "Couldn't share the logs (${e.javaClass.simpleName})."
                            }
                        }
                    }) { Icon(Icons.Filled.Share, contentDescription = "Share as .jsonl") }
                },
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("Filter text, event or field") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = errorsOnly, onClick = { errorsOnly = !errorsOnly }, label = { Text("Errors only") })
                    LEVEL_CHOICES.forEach { (level, label) ->
                        FilterChip(selected = minLevel == level, onClick = { minLevel = level }, label = { Text(label) })
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LogCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = cat in categories,
                            onClick = { categories = if (cat in categories) categories - cat else categories + cat },
                            label = { Text(cat.wire) },
                        )
                    }
                }
                val status = source.uploadStatus()
                Text(
                    buildString {
                        append(if (records == null) "Loading…" else "${shown.size} of ${records.orEmpty().size} lines, newest first")
                        if (status != null) append(" · $status")
                        shareError?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { "${it.seq}-${it.ts}" }) { record ->
                        LogRow(record)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(record: LogRecord) {
    var expanded by remember { mutableStateOf(false) }
    val color = levelColor(record.level)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row {
            Text(
                timeText(record),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                record.level.wire.uppercase(Locale.ROOT),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                record.event,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            record.msg,
            style = MaterialTheme.typography.bodySmall,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (expanded) {
            Box(Modifier.padding(top = 4.dp)) {
                SelectionContainer {
                    Text(
                        buildString {
                            record.fields.forEach { (k, v) -> appendLine("$k = ${LogJson.fieldText(v)}") }
                            append("seq ${record.seq} · ${record.context.state} · ${record.context.net}")
                            record.context.eventId?.let { append(" · event $it") }
                            record.context.diag?.let { append(" · $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun levelColor(level: LogLevel): Color? = when (level) {
    LogLevel.ERROR, LogLevel.FAULT -> MaterialTheme.colorScheme.error
    LogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
    LogLevel.NOTICE, LogLevel.INFO -> MaterialTheme.colorScheme.primary
    else -> null
}

private fun timeText(record: LogRecord): String =
    TIME_FORMAT.format(Instant.ofEpochMilli(record.epochMillis).atZone(ZoneId.systemDefault()))

/** Shares a `.jsonl` export through the system share sheet (FileProvider `<package>.logs`). */
private fun shareLogs(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "Attnd logs ${file.nameWithoutExtension.removePrefix("attnd-logs-")}")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(file.name, uri)
    try {
        context.startActivity(Intent.createChooser(send, "Share logs").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (e: ActivityNotFoundException) {
        AppLog.caught(LogCategory.LOGGING, "logs.share", e)
    }
}

private val LEVEL_CHOICES = listOf(
    LogLevel.TRACE to "All",
    LogLevel.INFO to "Info+",
    LogLevel.WARNING to "Warnings+",
)

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT)
