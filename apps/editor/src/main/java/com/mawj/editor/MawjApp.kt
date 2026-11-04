package com.mawj.editor

import android.app.Application
import com.mawj.editor.data.ProjectStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MawjApp : Application() {
    /** Work that should outlive a screen (e.g. auto backup). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var store: ProjectStore
        private set

    override fun onCreate() {
        super.onCreate()
        store = ProjectStore(this)
    }
}
