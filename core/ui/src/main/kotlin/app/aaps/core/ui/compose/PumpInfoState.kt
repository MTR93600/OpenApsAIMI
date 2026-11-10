package app.aaps.core.ui.compose

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The pump line of the main screen ("Pump: Reservoir 98 U | Site 4 d | Sensor 9 d" as simple HTML). The main screen
 * fills it in; the bottom bar shows it in a glass popup when the Pump button is held.
 */
object PumpInfoState {
    private val _html = MutableStateFlow("")
    val html: StateFlow<String> = _html.asStateFlow()

    fun update(value: String) {
        _html.value = value
    }
}
