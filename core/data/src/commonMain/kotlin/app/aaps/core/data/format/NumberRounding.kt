package app.aaps.core.data.format

/**
 * What to do with a value that sits exactly halfway between two renderable ones.
 *
 * Only reachable when the halfway point is exactly representable as a `Double`. That is less rare
 * than it sounds, so the choice is worth making rather than inheriting.
 *
 * At no decimals every `x.5` is a tie, because it is a dyadic rational. **At one decimal there are
 * ties too**: a tie is `(2k+1)/20`, and that fraction reduces whenever `2k+1` is a multiple of 5,
 * so `5/20 = 1/4`. `0.25`, `0.75`, `1.25` and `1.75` are all exactly representable one decimal
 * ties. The same happens at two decimals through `25/200 = 1/8`, giving `0.125`, `0.375` and
 * `0.625`.
 *
 * An earlier version of this note argued that the factor of 5 in `(2k+1)/20` meant no `Double` ever
 * landed on such a tie. That is wrong - the factor cancels - and it was believed, which is how a
 * half-even default replaced `String.format("%.1f", x)` in another module without anyone checking.
 * Code replacing a `%.Nf` call wants [HALF_UP]; see `NumberFormat.withDecimalsHalfUp`.
 */
enum class NumberRounding {

    /**
     * Ties go to the even neighbour: `0.5` renders as `0`, `1.5` as `2`.
     *
     * Banker's rounding. It exists to stop a bias from building up when many rounded values are
     * summed, and it is the default because it is what `DecimalFormat` has always done here.
     */
    HALF_EVEN,

    /**
     * Ties go away from zero: `0.5` renders as `1`.
     *
     * What a reader expects from a single number on screen, so it is the right choice for a value
     * shown on its own rather than one that will be added up.
     */
    HALF_UP
}
