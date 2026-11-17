package com.focuslock.app.util

import android.content.Context

/** Non-null system service lookup (the SDK declares getSystemService as nullable). */
inline fun <reified T : Any> Context.systemService(): T =
    requireNotNull(getSystemService(T::class.java)) { "Missing system service ${T::class.java.simpleName}" }
