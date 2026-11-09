package com.cmolnar65.swimlog

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Shows the Health Connect data-use disclosure from Android's permission UI. */
class HealthPrivacyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        content.addView(TextView(this).apply {
            text = "Swim Log Health Connect data use"
            textSize = 22f
            gravity = Gravity.START
        })
        content.addView(TextView(this).apply {
            text = HEALTH_DATA_DISCLOSURE
            textSize = 16f
            setPadding(0, 24, 0, 0)
        })

        setContentView(ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    companion object {
        internal const val HEALTH_DATA_DISCLOSURE =
            "This test app reads only the Health Connect data types you allow: exercise sessions, " +
                "distance, workout heart rate, weight, body fat, lean body mass, and blood pressure. " +
                "It sends selected records to the HTTPS Swim Log account you authorize in your browser. " +
                "The owner-scoped app token and short-lived sign-in proof are stored encrypted on this " +
                "device and are not included in backups or logs. The app never collects your WordPress " +
                "password. Health values are not written to app logs. The app does not " +
                "write to Health Connect, read GPS routes, or sync records older than 30 days. " +
                "Swim Log stores imported measurements privately for the account that owns the token."
    }
}
