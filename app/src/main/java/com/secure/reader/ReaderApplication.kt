package com.secure.reader

import android.app.Application
import com.secure.reader.core.di.AppContainer

class ReaderApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
