val forbiddenImportPrefixes = listOf(
    "android.",
    "androidx.",
    "dagger.",
    "hilt.",
    "javax.inject",
    "jakarta.inject",
    "app.aaps.database",
    "app.aaps.core.",
    "java.io.File",
    "okhttp3.",
    "retrofit2.",
    "org.json.",
    "kotlinx.coroutines.",
)

tasks.register("checkAimiDomainImports") {
    val srcRoot = layout.projectDirectory.dir("src")
    inputs.dir(srcRoot)
    doLast {
        val hits = mutableListOf<String>()
        val roots = listOf("commonMain", "jvmMain", "iosMain", "iosArm64Main", "iosSimulatorArm64Main")
            .map { file("src/$it") }
            .filter { it.exists() }
        roots.forEach { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { kt ->
                kt.readLines().forEachIndexed { idx, line ->
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("import ")) return@forEachIndexed
                    val imp = trimmed.removePrefix("import ").trim()
                    for (prefix in forbiddenImportPrefixes) {
                        if (imp == prefix || imp.startsWith(prefix)) {
                            hits += "${kt.relativeTo(project.projectDir)}:${idx + 1}: $trimmed"
                        }
                    }
                }
            }
        }
        if (hits.isNotEmpty()) {
            error(
                "Forbidden platform import in ${project.path}. " +
                    "The AIMI engine must not read Android, Room, prefs, files or coroutines.\n" +
                    hits.joinToString("\n")
            )
        }
    }
}

tasks.matching { it.name.startsWith("compileKotlin") }.configureEach {
    dependsOn("checkAimiDomainImports")
}
