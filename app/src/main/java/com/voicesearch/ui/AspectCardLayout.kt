package com.voicesearch.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Карточка результата: высоту задаёт ряд сетки, ширина — такая, чтобы постер
 * (первый ребёнок) был ровно 2:3, как постеры TMDB, и не обрезался.
 * Отступы постера берутся из его разметки и padding фона карточки.
 * Считается при каждом измерении, поэтому переиспользованная карточка не держит
 * ширину от прежней высоты списка (поиск и история отличаются по высоте).
 */
class AspectCardLayout(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val lp = getChildAt(0).layoutParams as ViewGroup.MarginLayoutParams
        // Фон карточки задаёт свой padding — постер живёт внутри него.
        val posterHeight = height - paddingTop - paddingBottom - lp.topMargin - lp.bottomMargin
        val width = posterHeight * 2 / 3 + paddingLeft + paddingRight + lp.marginStart + lp.marginEnd
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }
}
