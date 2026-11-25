package com.arcu.attnd.ui.screens

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arcu.attnd.ui.assistant.QrImage
import com.arcu.attnd.ui.components.LocalEventActions
import kotlinx.coroutines.launch

/**
 * The Schedule tab's "My Schedule" header with its Share button (iOS: the My Schedule section's Share): a sheet with a
 * QR code of the `/p/` link of My schedule's items (API-015, `PlanShare.scheduleLink`) and Share / Copy link.
 * The button shows only when there is an item to share.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyScheduleHeader(title: String) {
    val actions = LocalEventActions.current
    var link by remember { mutableStateOf<String?>(null) }
    val canShare = remember(actions.schedule, actions.bundle) { actions.scheduleShareLink() != null }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
        )
        if (canShare) {
            IconButton(onClick = { link = actions.scheduleShareLink() }) {
                Icon(Icons.Filled.QrCode2, contentDescription = "Share my schedule")
            }
        }
    }
    val shown = link ?: return
    ModalBottomSheet(onDismissRequest = { link = null }) {
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()
        var copied by remember { mutableStateOf(false) }
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Share my schedule", style = MaterialTheme.typography.titleMedium)
            QrImage(shown, size = 220.dp)
            Text(
                "Scan to open it in Attnd. Only the items are shared, nothing about you.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.onShareSchedule("share_sheet") }) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
                }
                OutlinedButton(onClick = {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Attnd link", shown))) }
                    copied = true
                    actions.onShareSchedule("copy")
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (copied) "Copied" else "Copy link")
                }
            }
        }
    }
}
