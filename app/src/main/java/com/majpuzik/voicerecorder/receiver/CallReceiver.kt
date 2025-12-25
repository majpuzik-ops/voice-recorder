package com.majpuzik.voicerecorder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.majpuzik.voicerecorder.data.AppSettings
import com.majpuzik.voicerecorder.service.CallRecordingService

class CallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallReceiver"
        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var isRecording = false
        private var savedNumber: String? = null
        private var isIncoming = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        val settings = AppSettings(context)

        // Check if auto call recording is enabled
        if (!settings.autoCallRecording) {
            Log.d(TAG, "Auto call recording is disabled")
            return
        }

        when (intent.action) {
            Intent.ACTION_NEW_OUTGOING_CALL -> {
                // Outgoing call - get the number
                savedNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER) ?: "unknown"
                isIncoming = false
                Log.d(TAG, "Outgoing call to: $savedNumber")
            }

            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

                val state = when (stateStr) {
                    TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
                    TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                    else -> TelephonyManager.CALL_STATE_IDLE
                }

                onCallStateChanged(context, state, number)
            }
        }
    }

    private fun onCallStateChanged(context: Context, state: Int, number: String?) {
        Log.d(TAG, "Call state changed: $lastState -> $state, number: $number")

        when {
            // Incoming call started ringing
            lastState == TelephonyManager.CALL_STATE_IDLE && state == TelephonyManager.CALL_STATE_RINGING -> {
                isIncoming = true
                savedNumber = number ?: "unknown"
                Log.d(TAG, "Incoming call from: $savedNumber")
            }

            // Call answered (incoming or outgoing)
            lastState == TelephonyManager.CALL_STATE_RINGING && state == TelephonyManager.CALL_STATE_OFFHOOK -> {
                // Incoming call answered
                startCallRecording(context)
            }

            lastState == TelephonyManager.CALL_STATE_IDLE && state == TelephonyManager.CALL_STATE_OFFHOOK -> {
                // Outgoing call started
                startCallRecording(context)
            }

            // Call ended
            (lastState == TelephonyManager.CALL_STATE_OFFHOOK || lastState == TelephonyManager.CALL_STATE_RINGING)
                    && state == TelephonyManager.CALL_STATE_IDLE -> {
                stopCallRecording(context)
            }
        }

        lastState = state
    }

    private fun startCallRecording(context: Context) {
        if (isRecording) {
            Log.d(TAG, "Already recording")
            return
        }

        Log.d(TAG, "Starting call recording for: $savedNumber, incoming: $isIncoming")

        val serviceIntent = Intent(context, CallRecordingService::class.java).apply {
            action = CallRecordingService.ACTION_START
            putExtra(CallRecordingService.EXTRA_PHONE_NUMBER, savedNumber ?: "unknown")
            putExtra(CallRecordingService.EXTRA_IS_INCOMING, isIncoming)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            isRecording = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording service: ${e.message}", e)
        }
    }

    private fun stopCallRecording(context: Context) {
        if (!isRecording) {
            Log.d(TAG, "Not recording, nothing to stop")
            return
        }

        Log.d(TAG, "Stopping call recording")

        val serviceIntent = Intent(context, CallRecordingService::class.java).apply {
            action = CallRecordingService.ACTION_STOP
        }

        try {
            context.startService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording service: ${e.message}", e)
        }

        isRecording = false
        savedNumber = null
    }
}
