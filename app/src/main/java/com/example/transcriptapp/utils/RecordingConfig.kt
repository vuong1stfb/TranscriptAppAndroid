package com.example.transcriptapp.utils

import android.util.DisplayMetrics
import kotlin.math.roundToInt

data class ScreenMetrics(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int
) {
    companion object {
        fun fromDisplayMetrics(metrics: DisplayMetrics): ScreenMetrics = ScreenMetrics(
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            densityDpi = metrics.densityDpi
        )
    }
}

data class RecordingDimensions(
    val widthPx: Int,
    val heightPx: Int
)

data class RecordingConfig(
    val screen: ScreenMetrics,
    val dimensions: RecordingDimensions
)

interface RecordingDimensionStrategy {
    fun calculate(metrics: ScreenMetrics): RecordingDimensions
}

class ScaledEvenDimensionStrategy(
    private val scaleFactor: Float = DEFAULT_SCALE_FACTOR,
    private val minWidth: Int = MIN_WIDTH,
    private val minHeight: Int = MIN_HEIGHT
) : RecordingDimensionStrategy {

    override fun calculate(metrics: ScreenMetrics): RecordingDimensions {
        val scaledWidth = (metrics.widthPx * scaleFactor).roundToInt().coerceAtLeast(minWidth)
        val scaledHeight = (metrics.heightPx * scaleFactor).roundToInt().coerceAtLeast(minHeight)

        return RecordingDimensions(
            widthPx = ensureEven(scaledWidth),
            heightPx = ensureEven(scaledHeight)
        )
    }

    private fun ensureEven(value: Int): Int = if (value % 2 == 0) value else value - 1

    companion object {
        private const val DEFAULT_SCALE_FACTOR = 0.3375f
        private const val MIN_WIDTH = 480
        private const val MIN_HEIGHT = 854
    }
}

object RecordingConfigFactory {
    private val dimensionStrategy: RecordingDimensionStrategy = ScaledEvenDimensionStrategy()

    fun create(metrics: DisplayMetrics): RecordingConfig {
        val screenMetrics = ScreenMetrics.fromDisplayMetrics(metrics)
        return RecordingConfig(
            screen = screenMetrics,
            dimensions = dimensionStrategy.calculate(screenMetrics)
        )
    }
}
