package com.mediacenter.app.ui.gallery

import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

object Dpad {

    fun isActivate(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_BUTTON_A
    }

    fun move(view: View, delta: Int): Boolean {
        val rv = view.parent as? RecyclerView ?: return false
        val current = rv.getChildAdapterPosition(view)
        if (current == RecyclerView.NO_POSITION) return false
        val target = current + delta
        if (target < 0 || target >= (rv.adapter?.itemCount ?: 0)) return true
        focusPosition(rv, target)
        return true
    }

    fun handleContentKey(
        view: View,
        keyCode: Int,
        event: KeyEvent,
        onFocusSidebar: () -> Unit,
    ): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val rv = view.parent as? RecyclerView ?: return false
        val position = rv.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return false
        val span = (rv.layoutManager as? GridLayoutManager)?.spanCount ?: 1
        val column = if (span > 1) position % span else 0
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (column == 0) {
                    onFocusSidebar()
                    true
                } else {
                    move(view, -1)
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (span > 1) move(view, 1) else false
            KeyEvent.KEYCODE_DPAD_UP -> move(view, -span)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(view, span)
            else -> false
        }
    }

    fun focusPosition(rv: RecyclerView, position: Int) {
        if (position < 0 || position >= (rv.adapter?.itemCount ?: 0)) return
        rv.scrollToPosition(position)
        rv.post {
            val child = rv.findViewHolderForAdapterPosition(position)?.itemView
            if (child != null) {
                child.requestFocus()
            } else {
                rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                            recyclerView.removeOnScrollListener(this)
                            recyclerView.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                        }
                    }
                })
            }
        }
    }

    fun bindItem(view: View) {
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.isClickable = true
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            view.defaultFocusHighlightEnabled = false
        }
    }
}
