package app.aaps.plugins.eversense.util

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.joran.spi.JoranException
import java.io.ByteArrayInputStream
import java.io.InputStream

class EversenseLogger {
    private val lc = LoggerContext()
    private var isEnabled: Boolean = true

    init {
        val config = JoranConfigurator()
        config.setContext(lc)

        val stream: InputStream = ByteArrayInputStream(LOGBACK_XML.toByteArray())
        try {
            config.doConfigure(stream)
        } catch (e: JoranException) {
            e.printStackTrace()
        }
    }

    private fun debug(tag: String, message: String) {
        if (!isEnabled) { return }

        lc.getLogger(tag).debug(logLocationPrefix() + message)
    }

    private fun info(tag: String, message: String) {
        if (!isEnabled) { return }
        lc.getLogger(tag).info(logLocationPrefix() + message)
    }

    private fun warning(tag: String, message: String) {
        if (!isEnabled) { return }
        lc.getLogger(tag).warn(logLocationPrefix() + message)
    }

    private fun error(tag: String, message: String) {
        if (!isEnabled) { return }
        lc.getLogger(tag).error(logLocationPrefix() + message)
    }

    fun enableLogging(value: Boolean) {
        this.isEnabled = value
    }

    private fun logLocationPrefix(): String {
        val stackInfo = Throwable().stackTrace[4]
        val className = stackInfo.className.substringAfterLast(".")
        val methodName = stackInfo.methodName
        val lineNumber = stackInfo.lineNumber

        return "$className.$methodName():$lineNumber]: "
    }

    companion object {
        val instance = EversenseLogger()

        fun debug(tag: String, message: String) {
            instance.debug(tag, message)
        }

        fun info(tag: String, message: String) {
            instance.info(tag, message)
        }

        fun warning(tag: String, message: String) {
            instance.warning(tag, message)
        }

        fun error(tag: String, message: String) {
            instance.error(tag, message)
        }

        private const val LOGBACK_XML: String = "<configuration>\n" +
            "    <!-- Write the log next to AndroidAPS.log, in the same directory the main logback.xml uses.\n" +
            "         EXT_DIR is filled in by logback-android: on Android 10 and newer it is the app's own\n" +
            "         external files directory, which needs no storage permission. This keeps the log\n" +
            "         reachable by adb pull and by the in-app log export, and it works for every flavour\n" +
            "         because no package name is hardcoded. -->\n" +
            "    <property name=\"EXT_FILES_DIR\" scope=\"context\" value=\"\${EXT_DIR:-/sdcard}/AndroidAPS/eversense\" />\n" +
            "    <appender name=\"file\" class=\"ch.qos.logback.core.rolling.RollingFileAppender\">\n" +
            "        <file>\${EXT_FILES_DIR}/Eversense.log</file>\n" +
            "        <rollingPolicy class=\"ch.qos.logback.core.rolling.TimeBasedRollingPolicy\">\n" +
            "            <!-- daily rollover. Make sure the path matches the one in the file element or else\n" +
            "             the rollover logs are placed in the working directory. -->\n" +
            "            <fileNamePattern>\${EXT_FILES_DIR}/Eversense._%d{yyyy-MM-dd}_%d{HH-mm-ss, aux}_.%i.zip\n" +
            "            </fileNamePattern>\n" +
            "\n" +
            "            <timeBasedFileNamingAndTriggeringPolicy\n" +
            "                class=\"ch.qos.logback.core.rolling.SizeAndTimeBasedFNATP\">\n" +
            "                <maxFileSize>5MB</maxFileSize>\n" +
            "            </timeBasedFileNamingAndTriggeringPolicy>\n" +
            "            <!-- keep 30 days' worth of history -->\n" +
            "            <maxHistory>120</maxHistory>\n" +
            "        </rollingPolicy>\n" +
            "        <encoder>\n" +
            "            <pattern>[%d{HH:mm:ss.SSS} %.-1level/%logger %msg%n</pattern>\n" +
            "        </encoder>\n" +
            "    </appender>\n" +
            "\n" +
            "    <appender name=\"logcat\" class=\"ch.qos.logback.classic.android.LogcatAppender\">\n" +
            "        <tagEncoder>\n" +
            "            <pattern>%logger{0}</pattern>\n" +
            "        </tagEncoder>\n" +
            "        <encoder>\n" +
            "            <pattern>[%d{HH:mm:ss.SSS} %msg%n</pattern>\n" +
            "        </encoder>\n" +
            "    </appender>\n" +
            "\n" +
            "    <!-- Write INFO (and higher-level) messages to the log file -->\n" +
            "    <root level=\"DEBUG\">\n" +
            "        <appender-ref ref=\"file\" />\n" +
            "        <appender-ref ref=\"logcat\" />\n" +
            "    </root>\n" +
            "</configuration>"

    }
}