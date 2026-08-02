package com.vibeplayer.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.widget.SeekBar
import kotlin.math.roundToInt

/** A TV timeline whose focus thumb is scaled uniformly instead of by the platform SeekBar. */
class TimelineSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle,
) : SeekBar(context, attrs, defStyleAttr) {

    private val defaultThumbDrawable = requireNotNull(context.getDrawable(R.drawable.player_seek_thumb_default))
    private val focusedThumbDrawable = requireNotNull(context.getDrawable(R.drawable.focus_gradient_thumb))

    init {
        // Android 9's SeekBar distorts state-list thumbs with different intrinsic sizes on this TV.
        // Draw the thumb ourselves so both axes always use the same explicit size.
        thumb = ColorDrawable(Color.TRANSPARENT)
        thumbOffset = 0
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val focused = hasFocus() || isPressed
        val drawable = if (focused) focusedThumbDrawable else defaultThumbDrawable
        val sizeDp = if (focused) FOCUSED_THUMB_DP else DEFAULT_THUMB_DP
        val sizePx = (sizeDp * resources.displayMetrics.density).roundToInt()
        val half = sizePx / 2
        val availableWidth = (width - paddingLeft - paddingRight - sizePx).coerceAtLeast(0)
        val fraction = if (max > 0) progress.toFloat() / max else 0f
        val centerX = paddingLeft + half + (availableWidth * fraction).roundToInt()
        val centerY = height / 2

        drawable.state = drawableState
        drawable.setBounds(centerX - half, centerY - half, centerX - half + sizePx, centerY - half + sizePx)
        drawable.draw(canvas)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    private companion object {
        const val DEFAULT_THUMB_DP = 17f
        const val FOCUSED_THUMB_DP = 23f
    }
}
