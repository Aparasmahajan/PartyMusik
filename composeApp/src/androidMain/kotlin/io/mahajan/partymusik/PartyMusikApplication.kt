package io.mahajan.partymusik

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.mahajan.partymusik.android.AndroidPlatform
import io.mahajan.partymusik.platform.DirectoryExportSink
import io.mahajan.partymusik.platform.EncryptedFileTextStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.lang.ref.WeakReference

/** Owns the shared core for the whole process so CompanionDeviceService wake-ups reach the same engine. */
class PartyMusikApplication : Application() {
    lateinit var platform: AndroidPlatform
        private set
    lateinit var core: PartyMusikCore
        private set

    private var currentActivity = WeakReference<Activity>(null)

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                currentActivity = WeakReference(activity)
                if (::platform.isInitialized) platform.permissions.refresh()
            }
            override fun onActivityPaused(activity: Activity) {
                if (currentActivity.get() === activity) currentActivity = WeakReference(null)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        platform = AndroidPlatform(this, scope) { currentActivity.get() }
        core = PartyMusikCore(
            platform.ports,
            EncryptedFileTextStore(File(filesDir, "partymusik.json")),
            scope,
            exportSink = DirectoryExportSink(getExternalFilesDir("exports") ?: File(filesDir, "exports")),
            diagnosticsStore = EncryptedFileTextStore(File(filesDir, "diagnostics.json")),
        )
        core.start()
    }
}
