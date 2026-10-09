package app.aaps.implementation.resources

import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.TextRef
import platform.Foundation.NSBundle
import platform.Foundation.NSString

/**
 * Resolves [TextRef] on iOS via NSLocalizedString.
 *
 * - [TextRef.Literal]: returned as-is, same as Android.
 * - [TextRef.Named]: looked up by [TextRef.Named.name] in the main bundle's Localizable strings.
 *   [TextRef.Named.owner] is ignored: iOS has a single strings table per bundle, and the owner
 *   only disambiguates Android modules that share a name with different translations. If the
 *   same name ever collides on iOS, the tables must be merged with prefixed keys instead.
 * - [TextRef.AndroidRes]: an Android resource id means nothing on iOS. Returns a "res:{id}"
 *   marker so the gap is visible in the UI rather than silently empty. Callers should migrate
 *   to [TextRef.Named].
 *
 * Format arguments are applied with NSString stringWithFormat, matching Android's String.format
 * behavior for the %s/%d verbs the codebase uses.
 */
class IosTextResolver : TextResolver {

    private fun resolveNamed(name: String): String =
        NSBundle.mainBundle.localizedStringForKey(name, value = name, table = null)

    private fun applyArgs(format: String, args: List<Any?>): String {
        if (args.isEmpty()) return format
        // ObjC uses %@ for objects; Android-style %s would be read as a C string
        // pointer, producing garbage. Rewrite %s (and positional %N$s) to %@ so
        // Kotlin Strings bridge to NSString correctly.
        val objcFormat = format.replace(Regex("%(\\d+\\$)?s"), "%\$1@")
        val nsArgs: List<Any?> = args.map { arg ->
            when (arg) {
                null -> "null"
                is String -> arg
                is Number -> arg
                is Boolean -> arg
                else -> arg.toString()
            }
        }
        return NSString.stringWithFormat(objcFormat, *nsArgs.toTypedArray())
    }

    override fun gs(ref: TextRef): String = when (ref) {
        is TextRef.Literal -> ref.text
        is TextRef.AndroidRes -> "res:${ref.id}"
        is TextRef.Named -> {
            val template = resolveNamed(ref.name)
            applyArgs(template, ref.args)
        }
    }

    override fun gs(ref: TextRef, vararg args: Any?): String =
        gs(ref.withArgs(*args))

    override fun gsNotLocalised(ref: TextRef): String = when (ref) {
        is TextRef.Literal -> ref.text
        is TextRef.AndroidRes -> "res:${ref.id}"
        is TextRef.Named -> {
            // Best effort: NSLocalizedString has no "always English" mode. Returns the
            // localized value; callers using this for stored/uploaded data should prefer
            // Literal refs for values that must not move with the locale.
            val template = resolveNamed(ref.name)
            applyArgs(template, ref.args)
        }
    }

    override fun shortTextMode(): Boolean = false
}
