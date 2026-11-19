package com.hotspotmonitor.agent.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.hotspotmonitor.agent.core.AgentGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shares files/logs/agent.log (and the rotated agent.log.1) through the app's FileProvider. */
object LogExport {
    /** FileProvider authority declared in the manifest (cache/exports and files/logs). */
    const val AUTHORITY = "com.hotspotmonitor.agent.files"

    fun share(context: Context, graph: AgentGraph) {
        graph.appScope.launch {
            val files = withContext(Dispatchers.IO) {
                graph.log.flush()
                graph.log.files()
            }
            withContext(Dispatchers.Main) {
                if (files.isEmpty()) {
                    toast(context, "No log file yet")
                    return@withContext
                }
                try {
                    val uris: List<Uri> = files.map { FileProvider.getUriForFile(context, AUTHORITY, it) }
                    val send = if (uris.size == 1) {
                        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
                    } else {
                        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    }
                    send.type = "text/plain"
                    send.putExtra(Intent.EXTRA_SUBJECT, "Hotspot Agent ${graph.agentVersion} log")
                    // ClipData carries the read grant to every file on all Android versions.
                    val clip = ClipData.newUri(context.contentResolver, "agent.log", uris.first())
                    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                    send.clipData = clip
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val chooser = Intent.createChooser(send, "Export Agent log")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(chooser)
                } catch (e: Exception) {
                    graph.log.w("ui", "Could not share the log", e)
                    toast(context, "Could not share the log: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }
}
