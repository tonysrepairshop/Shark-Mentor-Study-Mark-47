package com.example.sharkmentor.ui.components

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.sharkmentor.core.notification.ReflectionAlarmReceiver
import com.example.sharkmentor.service.sync.DailyReviewWorker
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReflectionTimeDialog(
    onDismiss: () -> Unit,
    onTimeSaved: (hour: Int, minute: Int) -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = context.getSharedPreferences("sharkmentor_prefs", Context.MODE_PRIVATE)
    
    // Default 22:30
    val savedHour = sharedPrefs.getInt("reflection_hour", 22)
    val savedMinute = sharedPrefs.getInt("reflection_minute", 30)

    val timePickerState = rememberTimePickerState(
        initialHour = savedHour,
        initialMinute = savedMinute,
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Günlük Hatırlatıcı Saati") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Günün değerlendirmesi için sana ne zaman hatırlatıcı gönderelim?",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                TimePicker(state = timePickerState)
            }
        },
        confirmButton = {
            Button(onClick = {
                val hour = timePickerState.hour
                val minute = timePickerState.minute
                
                sharedPrefs.edit()
                    .putInt("reflection_hour", hour)
                    .putInt("reflection_minute", minute)
                    .apply()
                
                scheduleReflectionAlarm(context, hour, minute)
                onTimeSaved(hour, minute)
                onDismiss()
            }) {
                Text("Kaydet")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("İptal")
            }
        }
    )
}

fun scheduleReflectionAlarm(context: Context, hour: Int, minute: Int) {
    DailyReviewWorker.scheduleDailyReview(context, hour, minute)
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, ReflectionAlarmReceiver::class.java)
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        ReflectionAlarmReceiver.NOTIFICATION_ID,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val calendar = Calendar.getInstance().apply {
        timeInMillis = System.currentTimeMillis()
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        
        if (timeInMillis <= System.currentTimeMillis()) {
            add(Calendar.DAY_OF_YEAR, 1)
        }
    }

    try {
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            pendingIntent
        )
    } catch (e: SecurityException) {
        // Can be handled if exact alarm permission is denied
    }
}
