package app.aaps.core.interfaces.ios

/**
 * Keeps AAPS running while the iPhone is in the background, as Spike did for its Garmin watch faces.
 *
 * iOS suspends an app in the background. AAPS is woken by the CGM for a few seconds every 5 minutes,
 * and a watch face that asks AAPS for data at another moment gets no answer, so its steps and heart
 * rate never arrive. While at least one owner wants it, the implementation plays silence through an
 * audio session that mixes with other apps, which iOS lets run in the background.
 *
 * It costs battery, so it is off unless an owner asks for it.
 */
interface IosKeepAwake {

    /** [owner] wants AAPS to stay awake, or no longer does. AAPS stays awake while any owner wants it. */
    fun setWanted(owner: String, wanted: Boolean)
}
