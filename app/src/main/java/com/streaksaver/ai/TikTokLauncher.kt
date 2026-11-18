package com.streaksaver.ai

import android.content.Context
import android.content.Intent
import android.net.Uri

object TikTokLauncher {
    fun openProfile(context: Context, username: String): Boolean {
        val clean = username.trim().removePrefix("@")
        if (clean.isBlank()) return false
        val uri = Uri.parse("https://www.tiktok.com/@" + Uri.encode(clean))
        return try {
            val appIntent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.zhiliaoapp.musically")
            context.startActivity(appIntent)
            true
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
