@file:Suppress("DEPRECATION", "UNCHECKED_CAST", "MemberVisibilityCanBePrivate", "unused")

package dev.inkremental.dsl.androidx.appcompat

import android.view.View
import androidx.appcompat.widget.Toolbar
import dev.inkremental.dsl.androidx.appcompat.widget.ToolbarScope
import dev.inkremental.Inkremental
import dev.inkremental.attr

fun ToolbarScope.layoutGravity(gravity: Int) = attr("layoutGravity", gravity)

object CustomAppCompatv7Setter : Inkremental.AttributeSetter<Any> {
    override fun set(v: View, name: String, value: Any?, prevValue: Any?): Boolean = when(name) {
        "layoutGravity" -> when {
            v.layoutParams is Toolbar.LayoutParams && value is Int -> {
                (v.layoutParams as Toolbar.LayoutParams).gravity = value
                true
            }
            else -> false
        }
        else -> false
    }
}