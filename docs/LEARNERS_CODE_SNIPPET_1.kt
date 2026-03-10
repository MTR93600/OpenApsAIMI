// 📊 Build learners summary for RT visibility (finalResult.learnersInfo)
val learnersParts = mutableListOf<String>()

// Basal Learner
val basalMult = basalLearner.getMultiplier()
if (kotlin.math.abs(basalMult - 1.0) > 0.01) {
    learnersParts.add("Basal×" + String.format(Locale.US, "%.2f", basalMult))
}

// PKPD Learner (ISF adjustment)
if (pkpdRuntime.learningFactor != 1.0) {
    learnersParts.add("ISF:" + pkpdRuntime.isf.toInt())
}

// Unified Reactivity Learner
val reactivityFactor = unifiedReactivityLearner.getCombinedFactor()
if (kotlin.math.abs(reactivityFactor - 1.0) > 0.01) {
    learnersParts.add("React×" + String.format(Locale.US, "%.2f", reactivityFactor))
}

val learnersSummary = learnersParts.joinToString(", ")
