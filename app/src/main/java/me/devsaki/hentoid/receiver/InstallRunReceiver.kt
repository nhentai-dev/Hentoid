package me.devsaki.hentoid.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

const val KEY_APK_PATH = "apk_path"

class InstallRunReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        /*
        val apkPath = intent.getStringExtra(KEY_APK_PATH)
        if (apkPath.isNullOrEmpty()) return

        val contentUri = FileProvider.getUriForFile(context, FileHelper.getFileProviderAuthority(), File(apkPath))
        ApkInstall().install(context, contentUri)
         */
        Timber.d("dummy")
    }
}

