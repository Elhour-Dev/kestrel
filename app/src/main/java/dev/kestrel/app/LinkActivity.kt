package dev.kestrel.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Receives links opened or shared from other apps and hands them to [MainActivity].
 * MainActivity is singleTask: when Android restarts it after the app was stopped, it gets the
 * old link again and can lose the new one. This screen always gets the new one, and runs in our process.
 */
class LinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MainActivity.Ui.pendingIntent = Intent(intent)
        startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
