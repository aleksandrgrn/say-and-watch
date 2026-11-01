package com.voicesearch.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Карточка результата: высоту задаёт ряд сетки, ширина — от постера (первый ребёнок)
 * 2:3, как постеры TMDB, и чуть растянута по [fitRow], чтобы ряд закрывал всю ширину.
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
        val width = fitRow(posterHeight * 2 / 3 + paddingLeft + paddingRight + lp.marginStart + lp.marginEnd)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }

    /**
     * Растягивает карточку так, чтобы целое число карточек с отступами [GAP_DP] ровно
     * закрывало ширину сетки: правый край совпадает с полем поиска и кнопками.
     * Прибавка — меньше одной карточки на весь ряд, постер (centerCrop) срезается на пару px.
     * Остаток от деления раздаётся по 1 px первым столбцам каждой «страницы» из count
     * столбцов — иначе справа недоставало бы до count−1 px.
     */
    private fun fitRow(idealWidth: Int): Int {
        val rv = parent as? RecyclerView
        val row = rv?.let { it.measuredWidth - it.paddingLeft - it.paddingRight } ?: 0
        if (row <= 0 || idealWidth <= 0) return idealWidth
        val gap = (GAP_DP * resources.displayMetrics.density).toInt()
        val count = maxOf(1, (row + gap) / (idealWidth + gap))
        val cards = row - (count - 1) * gap
        val span = (rv?.layoutManager as? GridLayoutManager)?.spanCount ?: 1
        val position = (layoutParams as? RecyclerView.LayoutParams)?.viewLayoutPosition ?: 0
        val column = (position.coerceAtLeast(0) / span) % count
        return cards / count + if (column < cards % count) 1 else 0
    }

    companion object {
        /** Отступ между карточками сетки; его же ставит ItemDecoration в SearchActivity. */
        const val GAP_DP = 12
    }
}
