package app.aaps.plugins.aimicontracts

/**
 * Public KMP façade for `:plugins:aimi-contracts`.
 *
 * [hello] proves Kotlin common code can be called from Swift through the Native framework.
 * It does not run AIMI. Snapshot types live beside this object; they are not computed here.
 */
object AimiContracts {

    fun hello(): String = "aimi-contracts"
}
