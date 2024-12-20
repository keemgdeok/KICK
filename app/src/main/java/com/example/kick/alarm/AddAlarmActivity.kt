package com.example.kick.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TimePicker
import androidx.appcompat.app.AppCompatActivity
import com.example.kick.R
import java.util.*

class AddAlarmActivity : AppCompatActivity() {

    private lateinit var timePicker: TimePicker
    private lateinit var saveButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.alarm_add)

        timePicker = findViewById(R.id.timePicker)
        saveButton = findViewById(R.id.saveButton)

        saveButton.setOnClickListener {
            val hour = timePicker.hour
            val minute = timePicker.minute
            val alarmId = System.currentTimeMillis().toInt()
            val alarm = Alarm(alarmId, hour, minute, true) // 새로운 알람 객체 생성

            // 알람 저장
            saveAlarm(alarm)
            Log.d("AddAlarmActivity", "Alarm saved: ${alarm.toJson()}")

            // AlarmManagerUtil을 사용해 알람 설정
            AlarmManagerUtil(this).scheduleAlarm(alarm)
            finish()
        }
    }

    private fun saveAlarm(alarm: Alarm) {
        val sharedPref = getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        val alarmJsonSet = sharedPref.getStringSet("alarms", mutableSetOf())?.toMutableSet() ?: mutableSetOf()

        // 알람 객체를 JSON으로 변환하여 Set에 추가
        alarmJsonSet.add(alarm.toJson())

        // 변경된 Set을 다시 저장
        with(sharedPref.edit()) {
            putStringSet("alarms", alarmJsonSet)
            apply()  // 데이터를 비동기적으로 저장
        }
    }
}
