package app.aaps.plugins.aps.openAPSAIMI.advisor.assistant

/** TIME steps decide when an SMB may go; SIZE steps decide how large it may be. */
enum class StageType { TIME, SIZE, OTHER }

/**
 * What one step of the SMB chain is, which settings change it (English names, as in the app) and a short
 * explanation in Russian. [verified] is true only when the link was read from the code.
 */
data class StageInfo(
    val name: String,
    val type: StageType,
    val settings: List<String>,
    val ru: String,
    val verified: Boolean,
)

object AssistantStageInfo {

    private val all = listOf(
        StageInfo(
            "REFRACTORY_AND_THYROID", StageType.TIME,
            listOf("Snack / Meal / Breakfast / Lunch / Dinner / Sleep / High-carb interval", "High BG interval"),
            "Ожидание между SMB. Пока с прошлой подачи не прошло нужное время, новая доза обнуляется. Это не потеря " +
                "дозы, а пауза. Вне режимов еды интервал берётся из High BG interval (при сахаре выше 6,7 ммоль/л), " +
                "а ниже 6,7 он всегда 3 минуты и настройкой не меняется. Тут же работает защита щитовидной железы.",
            true,
        ),
        StageInfo(
            "SAFETY_NET", StageType.SIZE,
            listOf("Max SMB", "Max SMB High BG"),
            "Предельный размер одного SMB. Это потолок из Max SMB (при обычном сахаре) и Max SMB High BG (при высоком). " +
                "Если доза упёрлась сюда, поднять потолок можно этими двумя настройками.",
            true,
        ),
        StageInfo(
            "SAFETY_PRECAUTIONS_PKPD", StageType.SIZE,
            emptyList(),
            "Снижение дозы по модели действия инсулина (PKPD): учитывает, сколько инсулина уже работает. Связь с " +
                "конкретными настройками ещё не проверена по коду.",
            false,
        ),
        StageInfo(
            "POST_HYPO_CAP", StageType.TIME,
            listOf("Post-hypo release margin", "Post-hypo release delta"),
            "Защита после низкого сахара. После гипо SMB обнуляется, пока сахар не поднимется на нужный запас над целью " +
                "и не растёт достаточно быстро. Запас и скорость роста задаются двумя настройками слева.",
            true,
        ),
        StageInfo(
            "PKPD_THROTTLE", StageType.SIZE,
            emptyList(),
            "Притормаживание по PKPD: уменьшает SMB и увеличивает паузу, когда инсулина на борту много. Связь с " +
                "настройками ещё не проверена по коду.",
            false,
        ),
        StageInfo(
            "RESTORE_CEILING", StageType.SIZE,
            listOf("Red Carpet restore threshold"),
            "Возврат доли срезанной дозы (Red Carpet). Работает только в приоритетной ситуации (режим еды, быстрый рост). " +
                "Связь по каталогу настроек, глубже по коду не проверена.",
            false,
        ),
        StageInfo(
            "AUTODRIVE_FLOOR", StageType.SIZE,
            listOf("Light snack pre-bolus", "Meal Pre-bolus", "Aggressive-rise SMB floor"),
            "Минимальный SMB при росте (малый и крупный уровень). Работает при сахаре от 6,7 ммоль/л и включённом Aggressive-rise SMB floor.",
            false,
        ),
        StageInfo(
            "PATTERN_CAP", StageType.SIZE, emptyList(),
            "Потолок по распознанному суточному шаблону. Связь с настройками ещё не проверена.", false,
        ),
        StageInfo(
            "HTR", StageType.SIZE, listOf("Hyper trajectory SMB release (HTR)"),
            "Высвобождение дозы при прогнозируемом высоком сахаре. Если здесь ноль срезов, настройка у тебя не мешает.", false,
        ),
        StageInfo(
            "RBT", StageType.SIZE, emptyList(),
            "Дополнительная проверка дозы. Связь с настройками ещё не проверена.", false,
        ),
        StageInfo(
            "SMB_EXECUTOR", StageType.OTHER, emptyList(),
            "Общий исполнитель: сюда попадает всё, что не подала предыдущая стадия. Само по себе ничего не ограничивает.", false,
        ),
    )

    private val byName = all.associateBy { it.name }

    fun info(name: String): StageInfo =
        byName[name] ?: StageInfo(name, StageType.OTHER, emptyList(), "Эта стадия пока не описана.", false)
}
