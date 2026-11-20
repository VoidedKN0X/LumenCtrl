package com.vknxworks.lumenctrl

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes

/**
 * The app's text resources, reachable from everywhere: screens, the view model and the model classes whose labels the
 * UI shows (slider names, filter names and so on). [MainActivity] sets it first thing in `onCreate`, before any of
 * those classes is used.
 */
object AppStrings {
    @Volatile private var resources: Resources? = null

    fun init(context: Context) {
        resources = context.applicationContext.resources
    }

    fun get(@StringRes id: Int, args: Array<out Any?>): String {
        val r = resources ?: return ""
        return if (args.isEmpty()) r.getString(id) else r.getString(id, *args)
    }
}

/**
 * The text for [id] from `strings.xml`. [args] fill its `%1$s`, `%2$s`… places, written out the same way a Kotlin
 * string template would write them.
 */
fun tr(@StringRes id: Int, vararg args: Any?): String = AppStrings.get(id, args)
