package app.aaps.core.interfaces.stats

import android.content.Context
import android.util.LongSparseArray
import android.widget.TableLayout

interface TirCalculator {

    fun calculate(days: Long, lowMgdl: Double, highMgdl: Double): LongSparseArray<TIR>
    fun calculateHour(lowMgdl: Double, highMgdl: Double): LongSparseArray<TIR>
    fun calculateDaily(lowMgdl: Double, highMgdl: Double): LongSparseArray<TIR>
    fun stats(context: Context): TableLayout
    fun averageTIR(tirs: LongSparseArray<TIR>): TIR?
}