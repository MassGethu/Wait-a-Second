package com.example.waitasecond.ui.home

import com.example.waitasecond.data.repository.SessionManager
import com.example.waitasecond.core.session.FocusMode

import androidx.lifecycle.ViewModel

class FocusViewModel : ViewModel() {
    val state = SessionManager.state
    fun startSession(topic: String, minutes: Int, mode: FocusMode) = SessionManager.startSession(topic, minutes, mode)
    fun endSession() = SessionManager.endSession()
}
