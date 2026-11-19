package com.hotspotmonitor.agent.control

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.hotspotmonitor.agent.core.ActionResult
import com.hotspotmonitor.agent.core.AgentLog
import com.hotspotmonitor.agent.core.Ids
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Sends SMS through SmsManager (multipart) and waits for the radio's "sent" result of every part. */
internal class SmsSender(private val context: Context, private val log: AgentLog) {

    @SuppressLint("MissingPermission") // checked at the top
    suspend fun send(to: String, body: String, timeoutMs: Long = SEND_TIMEOUT_MS): ActionResult {
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return ActionResult.failure("Permission SEND_SMS is not granted", method = METHOD)
        }
        val maybeManager: SmsManager? = try {
            smsManager()
        } catch (e: Exception) {
            log.w(TAG, "SmsManager unavailable", e)
            null
        }
        val manager = maybeManager ?: return ActionResult.failure("SmsManager is not available on this phone", method = METHOD)

        val parts: ArrayList<String> = try {
            manager.divideMessage(body)
        } catch (e: Exception) {
            return ActionResult.failure("Could not split the message: ${e.message ?: e.javaClass.simpleName}", method = METHOD)
        }
        if (parts.isEmpty()) return ActionResult.failure("Empty message", method = METHOD)

        val action = "${context.packageName}.SMS_SENT.${Ids.random(10)}"
        val results = IntArray(parts.size) { PENDING }
        val allReported = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val part = intent?.getIntExtra(EXTRA_PART, -1) ?: -1
                val code = resultCode
                synchronized(results) {
                    if (part !in results.indices || results[part] != PENDING) return
                    results[part] = code
                    if (results.none { it == PENDING }) allReported.complete(Unit)
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            for (i in parts.indices) {
                val intent = Intent(action).setPackage(context.packageName).putExtra(EXTRA_PART, i)
                sentIntents += PendingIntent.getBroadcast(
                    context, i, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                )
            }
            withContext(Dispatchers.IO) {
                if (parts.size == 1) {
                    manager.sendTextMessage(to, null, parts[0], sentIntents[0], null)
                } else {
                    manager.sendMultipartTextMessage(to, null, parts, sentIntents, null)
                }
            }
            val completed = withTimeoutOrNull(timeoutMs) { allReported.await() } != null
            val snapshot = synchronized(results) { results.copyOf() }
            val failed = snapshot.withIndex().filter { it.value != Activity.RESULT_OK }
            return when {
                failed.isEmpty() -> ActionResult.success("Sent to $to in ${parts.size} part(s)", METHOD)
                !completed && failed.all { it.value == PENDING } ->
                    ActionResult.failure("No send confirmation within ${timeoutMs / 1000} s", method = METHOD)
                else -> {
                    val firstError = failed.firstOrNull { it.value != PENDING }?.value ?: PENDING
                    ActionResult.failure(
                        "${failed.size} of ${parts.size} part(s) not sent: ${describe(firstError)}",
                        method = METHOD,
                    )
                }
            }
        } catch (e: SecurityException) {
            return ActionResult.failure("Not allowed to send SMS: ${e.message ?: "SecurityException"}", method = METHOD)
        } catch (e: IllegalArgumentException) {
            return ActionResult.failure("Invalid SMS: ${e.message ?: "IllegalArgumentException"}", method = METHOD)
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                log.w(TAG, "SMS receiver was not registered")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager? {
        val subId = SubscriptionManager.getDefaultSmsSubscriptionId()
        val hasSub = subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java) ?: return null
            if (hasSub) base.createForSubscriptionId(subId) else base
        } else {
            if (hasSub) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }
    }

    private fun describe(code: Int): String = when (code) {
        PENDING -> "no result"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off"
        SmsManager.RESULT_ERROR_NULL_PDU -> "null PDU"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
        else -> "error code $code"
    }

    companion object {
        private const val TAG = "control"
        private const val METHOD = "SmsManager"
        private const val EXTRA_PART = "part"
        private const val PENDING = Int.MIN_VALUE
        const val SEND_TIMEOUT_MS = 60_000L
    }
}

/** Runs a USSD code through TelephonyManager.sendUssdRequest and returns the network's reply. */
internal class UssdRunner(private val context: Context) {

    @SuppressLint("MissingPermission") // checked at the top
    suspend fun run(code: String, timeoutMs: Long = USSD_TIMEOUT_MS): ActionResult {
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return ActionResult.failure("Permission CALL_PHONE is not granted", method = METHOD)
        }
        val base = context.getSystemService(TelephonyManager::class.java)
            ?: return ActionResult.failure("TelephonyManager is not available", method = METHOD)
        val subId = SubscriptionManager.getDefaultSubscriptionId()
        val tm = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) base.createForSubscriptionId(subId) else base

        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<ActionResult> { cont ->
                val callback = object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(
                        telephonyManager: TelephonyManager?,
                        request: String?,
                        response: CharSequence?,
                    ) {
                        if (cont.isActive) cont.resume(ActionResult.success(response?.toString().orEmpty(), METHOD))
                    }

                    override fun onReceiveUssdResponseFailed(
                        telephonyManager: TelephonyManager?,
                        request: String?,
                        failureCode: Int,
                    ) {
                        if (cont.isActive) cont.resume(ActionResult.failure(describeFailure(failureCode), method = METHOD))
                    }
                }
                try {
                    tm.sendUssdRequest(code, callback, Handler(Looper.getMainLooper()))
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resume(ActionResult.failure("Not allowed to run USSD: ${e.message}", method = METHOD))
                } catch (e: Exception) {
                    if (cont.isActive) {
                        cont.resume(ActionResult.failure("USSD request failed: ${e.message ?: e.javaClass.simpleName}", method = METHOD))
                    }
                }
            }
        }
        return result ?: ActionResult.failure("No USSD reply within ${timeoutMs / 1000} s", method = METHOD)
    }

    private fun describeFailure(code: Int): String = when (code) {
        TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL -> "USSD service unavailable"
        TelephonyManager.USSD_RETURN_FAILURE -> "the network rejected the USSD request"
        else -> "USSD failed (code $code)"
    }

    companion object {
        private const val METHOD = "TelephonyManager.sendUssdRequest"
        const val USSD_TIMEOUT_MS = 30_000L
    }
}
