package com.measurecam.util

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * 限高的 ScrollView。速录面板字段数量不定，
 * 超过屏高一半就内部滚动，不会把对话框撑出屏幕。
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    /** 小于等于 0 表示不限高。避免用 Int.MAX_VALUE 造成 MeasureSpec 溢出。 */
    var maxHeight: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (maxHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val capped = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, capped)
    }
}
