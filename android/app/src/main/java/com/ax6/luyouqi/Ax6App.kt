package com.ax6.luyouqi

import android.app.Application

class Ax6App : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
    }
}
