package com.bigmoe.onedge.sync

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.bigmoe.onedge.MainActivity
import com.bigmoe.onedge.OnEdgeApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Catches the OneDrive sign-in redirect (msal<client id>://auth?code=...) and returns to the app. */
class AuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri != null) {
            val sync = (application as OnEdgeApplication).container.chatSync
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { sync.completeSignIn(uri) }
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
