package com.example.waitasecond

import com.example.waitasecond.data.repository.SessionManager

import android.app.Application

class FocusApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SessionManager.initialize(this)
    }
}
