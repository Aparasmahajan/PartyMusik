package io.mahajan.partymusik

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.mahajan.partymusik.ui.App
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {

    private var controller: ListenableFuture<MediaController>? = null
    private var pending: CompletableDeferred<Map<String, Boolean>>? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        pending?.complete(result)
        pending = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as PartyMusikApplication

        // Just-in-time permission requests from shared code go through the Activity Result API.
        app.platform.permissions.requester = { perms ->
            val deferred = CompletableDeferred<Map<String, Boolean>>()
            pending = deferred
            permissionLauncher.launch(perms)
            deferred.await()
        }

        setContent { App(app.core) }
    }

    override fun onStart() {
        super.onStart()
        // Binding a controller starts PlaybackService; Media3 then shows the media notification while playing.
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
    }

    override fun onStop() {
        controller?.let(MediaController::releaseFuture)
        controller = null
        super.onStop()
    }

    override fun onDestroy() {
        (application as PartyMusikApplication).platform.permissions.requester = null
        super.onDestroy()
    }
}
