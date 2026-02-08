package app.aaps.plugins.sync.nsShared

/**
 * Mode preset for Remote Control.
 * Maps user-friendly names to Therapy.kt keywords.
 */
data class ModePreset(
    val id: String,
    val displayName: String,
    val therapyKeyword: String,  // Keyword that Therapy.kt searches for
    val icon: Int,
    val defaultDurationMin: Int,
    val category: ModeCategory,
    val description: String = ""
)

enum class ModeCategory {
    MEAL,          // Triggers P1/P2 prebolus
    ACTIVITY,      // Sport, sleep (shown in MODES tab)
    CONTEXT_ONLY,  // Activity contexts (shown only in CONTEXTS tab)
    PHYSIO,        // Stress, illness
    CONTROL        // Stop, fasting
}

/**
 * All available modes for remote control.
 */
object ModePresets {
    
    val ALL_MODES = listOf(
        // ═══════════════════════════════════════════════════════════
        // MEAL MODES - Trigger P1/P2 Prebolus
        // ═══════════════════════════════════════════════════════════
        ModePreset(
            id = "breakfast",
            displayName = "Petit-déjeuner",
            therapyKeyword = "bfast",
            icon = app.aaps.core.ui.R.drawable.ic_home,
            defaultDurationMin = 60,
            category = ModeCategory.MEAL,
            description = "Déclenche prébolus P1 et P2"
        ),
        ModePreset(
            id = "lunch",
            displayName = "Déjeuner",
            therapyKeyword = "lunch",
            icon = app.aaps.core.ui.R.drawable.ic_home,
            defaultDurationMin = 60,
            category = ModeCategory.MEAL,
            description = "Déclenche prébolus P1 et P2"
        ),
        ModePreset(
            id = "dinner",
            displayName = "Dîner",
            therapyKeyword = "dinner",
            icon = app.aaps.core.ui.R.drawable.ic_home,
            defaultDurationMin = 60,
            category = ModeCategory.MEAL,
            description = "Déclenche prébolus P1 et P2"
        ),
        ModePreset(
            id = "snack",
            displayName = "Collation",
            therapyKeyword = "snack",
            icon = app.aaps.core.ui.R.drawable.ic_home,
            defaultDurationMin = 30,
            category = ModeCategory.MEAL,
            description = "Déclenche prébolus P1 et P2"
        ),
        ModePreset(
            id = "highcarb",
            displayName = "Repas riche",
            therapyKeyword = "highcarb",
            icon = app.aaps.core.ui.R.drawable.ic_home,
            defaultDurationMin = 90,
            category = ModeCategory.MEAL,
            description = "Déclenche prébolus P1 et P2 renforcés"
        ),
        ModePreset(
            id = "meal",
            displayName = "Repas (général)",
            therapyKeyword = "meal",
            icon = app.aaps.core.ui.R.drawable.ic_home, // TODO: Add restaurant icon
            defaultDurationMin = 60,
            category = ModeCategory.MEAL,
            description = "Mode repas générique"
        ),
        
        // ═══════════════════════════════════════════════════════════
        // ACTIVITY MODES (shown in MODES tab)
        // ═══════════════════════════════════════════════════════════
        ModePreset(
            id = "sport",
            displayName = "Sport",
            therapyKeyword = "sport",
            icon = app.aaps.core.ui.R.drawable.ic_home, // TODO: Use ic_directions_run
            defaultDurationMin = 120,
            category = ModeCategory.ACTIVITY,
            description = "Réduit SMB pendant l'activité"
        ),
        ModePreset(
            id = "sleep",
            displayName = "Sommeil",
            therapyKeyword = "sleep",
            icon = app.aaps.core.ui.R.drawable.ic_home, // TODO: Add sleep icon
            defaultDurationMin = 480,
            category = ModeCategory.ACTIVITY,
            description = "Mode nuit sécurisé"
        ),
        
        // ═══════════════════════════════════════════════════════════
        // CONTEXT-ONLY (shown only in CONTEXTS tab, not in MODES)
        // ═══════════════════════════════════════════════════════════
        ModePreset(
            id = "cardio",
            displayName = "Cardio",
            therapyKeyword = "cardio",
            icon = app.aaps.core.ui.R.drawable.ic_home, // TODO: ic_directions_run
            defaultDurationMin = 60,
            category = ModeCategory.CONTEXT_ONLY,
            description = "Course, vélo, natation"
        ),
        ModePreset(
            id = "strength",
            displayName = "Musculation",
            therapyKeyword = "strength",
            icon = app.aaps.core.ui.R.drawable.ic_home, // TODO: ic_fitness_center
            defaultDurationMin = 45,
            category = ModeCategory.CONTEXT_ONLY,
            description = "Exercices de force"
        ),
        ModePreset(
            id = "yoga",
            displayName = "Yoga",
            therapyKeyword = "yoga",
            icon = app.aaps.core.ui.R.drawable.ic_home, // TODO: ic_self_improvement
            defaultDurationMin = 60,
            category = ModeCategory.CONTEXT_ONLY,
            description = "Yoga, stretching"
        ),
        ModePreset(
            id = "walking",
            displayName = "Marche",
            therapyKeyword = "walking",
            icon = app.aaps.core.ui.R.drawable.ic_home, // TODO: ic_directions_walk
            defaultDurationMin = 30,
            category = ModeCategory.CONTEXT_ONLY,
            description = "Marche légère"
        ),
        
        // ═══════════════════════════════════════════════════════════
        // PHYSIOLOGICAL MODES
        // ═══════════════════════════════════════════════════════════
        ModePreset(
            id = "stress",
            displayName = "Stress",
            therapyKeyword = "stress",
            icon = app.aaps.core.ui.R.drawable.ic_home, // TODO: Add stress icon
            defaultDurationMin = 180,
            category = ModeCategory.PHYSIO,
            description = "Augmente basale (stress hormonal)"
        ),
        ModePreset(
            id = "illness",
            displayName = "Maladie",
            therapyKeyword = "illness",
            icon = app.aaps.core.ui.R.drawable.ic_home, // TODO: Add sick icon
            defaultDurationMin = 480,
            category = ModeCategory.PHYSIO,
            description = "Résistance à l'insuline accrue"
        ),
        ModePreset(
            id = "gastro",
            displayName = "Gastro",
            therapyKeyword = "gastro",
            icon = app.aaps.core.ui.R.drawable.ic_home, // TODO: Add gastro icon
            defaultDurationMin = 480,
            category = ModeCategory.PHYSIO,
            description = "Troubles digestifs"
        ),
        ModePreset(
            id = "work_stress",
            displayName = "Stress travail",
            therapyKeyword = "work stress",
            icon = app.aaps.core.ui.R.drawable.ic_home, // TODO: Add work icon
            defaultDurationMin = 240,
            category = ModeCategory.PHYSIO,
            description = "Stress professionnel"
        ),
        ModePreset(
            id = "exam_stress",
            displayName = "Stress examen",
            therapyKeyword = "exam stress",
            icon = app.aaps.core.ui.R.drawable.ic_home, // TODO: Add school icon
            defaultDurationMin = 180,
            category = ModeCategory.PHYSIO,
            description = "Stress d'examen"
        ),
        ModePreset(
            id = "alcohol",
            displayName = "Alcool",
            therapyKeyword = "alcohol",
            icon = app.aaps.core.ui.R.drawable.ic_home, // TODO: Add bar icon
            defaultDurationMin = 360,
            category = ModeCategory.PHYSIO,
            description = "Consommation d'alcool"
        ),
        ModePreset(
            id = "travel",
            displayName = "Voyage",
            therapyKeyword = "travel",
            icon = app.aaps.core.ui.R.drawable.ic_home, // TODO: Add flight icon
            defaultDurationMin = 720,
            category = ModeCategory.PHYSIO,
            description = "Voyage / décalage horaire"
        ),
        
        // ═══════════════════════════════════════════════════════════
        // CONTROL MODES
        // ═══════════════════════════════════════════════════════════
        ModePreset(
            id = "fasting",
            displayName = "Jeûne",
            therapyKeyword = "fasting",
            icon = app.aaps.core.ui.R.drawable.ic_home, // TODO: Add fasting icon
            defaultDurationMin = 720,
            category = ModeCategory.CONTROL,
            description = "Mode jeûne (réduit basale)"
        ),
        ModePreset(
            id = "lowcarb",
            displayName = "Low Carb",
            therapyKeyword = "lowcarb",
            icon = app.aaps.core.ui.R.drawable.ic_home, // TODO: Add lowcarb icon
            defaultDurationMin = 180,
            category = ModeCategory.CONTROL,
            description = "Adaptation régime pauvre en glucides"
        ),
        ModePreset(
            id = "stop",
            displayName = "⛔ Annuler tout",
            therapyKeyword = "stop",
            icon = app.aaps.core.ui.R.drawable.ic_home, // TODO: Add stop icon
            defaultDurationMin = 0,
            category = ModeCategory.CONTROL,
            description = "Annule tous les modes actifs"
        )
    )
    
    
    /**
     * Find mode by therapy keyword (for parsing active modes).
     */
    fun findByKeyword(keyword: String): ModePreset? {
        return ALL_MODES.find { 
            it.therapyKeyword.equals(keyword, ignoreCase = true) 
        }
    }
}
