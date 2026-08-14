package com.mediacenter.app.ui.gallery

import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

object Dpad {

    fun isFavoriteAction(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_MENU ||
            keyCode == KeyEvent.KEYCODE_INFO ||
            keyCode == KeyEvent.KEYCODE_BOOKMARK ||
            keyCode == KeyEvent.KEYCODE_BUTTON_Y
    }

    fun isActivate(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            keyCode == KeyEvent.KEYCODE_BUTTON_SELECT
    }

    fun isPlayPause(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_BUTTON_A
    }

    fun isSeekBack(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_MEDIA_REWIND ||
            keyCode == KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD
    }

    fun isSeekForward(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
            keyCode == KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD
    }

    fun isPrevious(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
            keyCode == KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD
    }

    fun isNext(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
            keyCode == KeyEvent.KEYCODE_MEDIA_STEP_FORWARD
    }

    fun move(view: View, delta: Int): Boolean {
        val rv = view.parent as? RecyclerView ?: return false
        val current = rv.getChildAdapterPosition(view)
        if (current == RecyclerView.NO_POSITION) return false
        val count = rv.adapter?.itemCount ?: 0
        val target = current + delta
        if (target !in 0 until count) return true
        focusPosition(rv, target)
        return true
    }

    fun handleContentKey(
        view: View,
        keyCode: Int,
        event: KeyEvent,
        onFocusSidebar: () -> Unit,
        onFocusToolbar: () -> Unit = {},
    ): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val rv = view.parent as? RecyclerView ?: return false
        val position = rv.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return false
        val adapter = rv.adapter as? MediaAdapter
        val manager = rv.layoutManager as? GridLayoutManager
        val span = manager?.spanCount ?: 1
        val column = if (manager != null) {
            manager.spanSizeLookup.getSpanIndex(position, span)
        } else {
            0
        }
        val header = adapter?.isHeader(position) == true
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (header || column == 0) {
                    onFocusSidebar()
                    true
                } else {
                    step(rv, adapter, position, -1)
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (header || span <= 1) false else step(rv, adapter, position, 1)
            }
            KeyEvent.KEYCODE_DPAD_UP ->
                moveVertical(rv, adapter, position, span, -1, onFocusToolbar)
            KeyEvent.KEYCODE_DPAD_DOWN ->
                moveVertical(rv, adapter, position, span, 1, onFocusToolbar)
            else -> false
        }
    }

    private fun step(
        rv: RecyclerView,
        adapter: MediaAdapter?,
        position: Int,
        delta: Int,
    ): Boolean {
        val count = rv.adapter?.itemCount ?: 0
        var target = position + delta
        while (target in 0 until count && adapter?.isHeader(target) == true) {
            target += delta
        }
        if (target in 0 until count) focusPosition(rv, target)
        return true
    }

    private fun moveVertical(
        rv: RecyclerView,
        adapter: MediaAdapter?,
        position: Int,
        span: Int,
        direction: Int,
        onFocusToolbar: () -> Unit,
    ): Boolean {
        val count = rv.adapter?.itemCount ?: 0
        if (adapter == null) {
            val target = position + direction * span
            if (target in 0 until count) {
                focusPosition(rv, target)
            } else if (direction < 0) {
                onFocusToolbar()
            }
            return true
        }
        if (adapter.isHeader(position)) {
            val next = if (direction < 0) {
                adapter.previousFocusable(position)
            } else {
                adapter.nextFocusable(position)
            }
            if (next >= 0) {
                focusPosition(rv, next)
            } else if (direction < 0) {
                onFocusToolbar()
            }
            return true
        }
        val candidate = position + direction * span
        if (candidate in 0 until count &&
            !adapter.isHeader(candidate) &&
            adapter.sameSection(position, candidate)
        ) {
            focusPosition(rv, candidate)
            return true
        }
        if (direction < 0) {
            val header = adapter.headerPositionOf(position)
            if (header >= 0) {
                focusPosition(rv, header)
            } else {
                onFocusToolbar()
            }
        } else {
            val next = adapter.nextHeaderAfter(position)
            if (next >= 0) focusPosition(rv, next)
        }
        return true
    }

    fun requestFocusIfRemote(view: View) {
        if (!view.isInTouchMode) view.requestFocus()
    }

    fun focusPosition(rv: RecyclerView, position: Int) {
        if (position < 0 || position >= (rv.adapter?.itemCount ?: 0)) return
        rv.scrollToPosition(position)
        if (rv.isInTouchMode) return
        rv.post {
            if (rv.isInTouchMode) return@post
            val child = rv.findViewHolderForAdapterPosition(position)?.itemView
            if (child != null) {
                child.requestFocus()
            } else {
                rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                            recyclerView.removeOnScrollListener(this)
                            if (!recyclerView.isInTouchMode) {
                                recyclerView.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                            }
                        }
                    }
                })
            }
        }
    }

    fun bindItem(view: View) {
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        view.isClickable = true
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            view.defaultFocusHighlightEnabled = false
        }
    }
}
