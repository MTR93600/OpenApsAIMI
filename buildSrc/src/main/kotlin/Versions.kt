import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

@Suppress("ConstPropertyName")
object Versions {

    // On change edit aaps-ci.yml (fork builds use AIMI suffix; base tracks upstream 4.0.0-dev-b)
    const val appVersion = "4.0.0.0-dev.AIMI.260826"
    const val versionCode = 1500

    const val compileSdk = 37
    const val minSdk = 31
    const val targetSdk = 34 // Health Connect / Android 14 compatibility
    const val wearMinSdk = 30
    const val wearTargetSdk = 30

    val javaVersion = JavaVersion.VERSION_21
    val jvmTarget = JvmTarget.JVM_21
    const val jacoco = "0.8.11"
}
