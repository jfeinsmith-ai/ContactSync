package com.contactsync.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "ContactSync"
            textSize = 24f
            setPadding(32, 64, 32, 32)
        })
    }
}

