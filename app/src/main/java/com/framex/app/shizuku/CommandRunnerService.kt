package com.framex.app.shizuku

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

class CommandRunnerService(private val context: Context) : ICommandRunner.Stub() {

    /** Which mechanism successfully returned thermal sensor data. See [resolvedThermalStrategy]. */
    private enum class ThermalReadStrategy {
        REFLECTION,
        DUMPSYS,
        SYSFS
    }

    /**
     * Which read path successfully returned valid sensor data last time. Cached so that,
     * on devices where IThermalService reflection and dumpsys both fail (e.g. Qualcomm
     * legacy/Samsung Galaxy Tab A Lite hardware, see issue #57), we do not re-run the full
     * reflection scan and a doomed dumpsys attempt on every 1-second poll tick before
     * falling through to the sysfs read that actually works. Reset to null if the cached
     * strategy ever stops returning valid data, so behavior self-heals after an OTA or
     * permission change instead of getting stuck on a now-broken path.
     */
    @Volatile
    private var resolvedThermalStrategy: ThermalReadStrategy? = null

    override fun executeCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            process.waitFor()
            output.toString().trim()
        } catch (e: Exception) {
            com.framex.app.utils.FrameXLog.e("Error executing command: $command", e)
            "Error executing command: ${e.message}"
        }
    }

    override fun getThermalTemperatures(): String {
        resolvedThermalStrategy?.let { cached ->
            val cachedResult = readUsingStrategy(cached)
            if (cachedResult != null) return cachedResult
            // Cached strategy stopped working (hardware state changed at runtime) --
            // clear it and fall through to full re-discovery below.
            resolvedThermalStrategy = null
        }

        readViaReflection()?.let {
            resolvedThermalStrategy = ThermalReadStrategy.REFLECTION
            return it
        }
        readViaDumpsys()?.let {
            resolvedThermalStrategy = ThermalReadStrategy.DUMPSYS
            return it
        }
        readViaSysfs()?.let {
            resolvedThermalStrategy = ThermalReadStrategy.SYSFS
            return it
        }

        // All strategies failed validation: still return the raw dumpsys dump so callers
        // (ThermalMonitor's parser) can surface a ParseFailed status rather than getting
        // an empty string, matching prior behavior.
        return readViaDumpsys(requireValid = false).orEmpty()
    }

    private fun readUsingStrategy(strategy: ThermalReadStrategy): String? = when (strategy) {
        ThermalReadStrategy.REFLECTION -> readViaReflection()
        ThermalReadStrategy.DUMPSYS -> readViaDumpsys()
        ThermalReadStrategy.SYSFS -> readViaSysfs()
    }

    /**
     * Reads sensor temperatures via reflection into the hidden android.os.IThermalService
     * Binder, matching whichever getCurrentTemperatures* overload the device's Android
     * version exposes. Returns null (rather than an empty/invalid string) when no method
     * yields usable sensor data, so callers can fall through to the next strategy.
     */
    private fun readViaReflection(): String? {
        return try {
            val binderClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = binderClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "thermalservice") as? android.os.IBinder
                ?: return null
            val stubClass = Class.forName("android.os.IThermalService\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
            val service = asInterfaceMethod.invoke(null, binder) ?: return null

            val temps = invokeTemperatureGetter(service) ?: return null
            parseTemperatureObjects(temps)
        } catch (e: Exception) {
            com.framex.app.utils.FrameXLog.w("readViaReflection failed: ${e.message}", null, "CmdRunner")
            null
        }
    }

    /**
     * Tries every IThermalService method whose name contains "Temperature" with each of
     * the parameter-count overloads seen across Android versions (0, 1, and 2 args),
     * since the exact signature (getCurrentTemperatures, getCurrentTemperaturesWithType,
     * etc.) varies by OEM and API level.
     */
    private fun invokeTemperatureGetter(service: Any): Array<*>? {
        val candidateMethods = service.javaClass.methods.filter {
            it.name.contains("Temperature", ignoreCase = true)
        }
        for (method in candidateMethods) {
            val result = runCatching { invokeWithBestGuessArgs(method, service) }.getOrNull()
            when {
                result is Array<*> && result.isNotEmpty() -> return result
                result is List<*> && result.isNotEmpty() -> return result.toTypedArray()
            }
        }
        return null
    }

    private fun invokeWithBestGuessArgs(method: java.lang.reflect.Method, service: Any): Any? {
        val paramTypes = method.parameterTypes
        return when (paramTypes.size) {
            0 -> method.invoke(service)
            1 -> {
                val isBoolean = paramTypes[0] == Boolean::class.javaPrimitiveType || paramTypes[0] == Boolean::class.java
                if (isBoolean) method.invoke(service, false) else method.invoke(service, 0)
            }
            2 -> {
                val (p0, p1) = paramTypes[0] to paramTypes[1]
                when {
                    p0 == Boolean::class.javaPrimitiveType && p1 == Int::class.javaPrimitiveType -> method.invoke(service, false, 0)
                    p0 == Int::class.javaPrimitiveType && p1 == Boolean::class.javaPrimitiveType -> method.invoke(service, 0, false)
                    else -> method.invoke(service, false, 0)
                }
            }
            else -> null
        }
    }

    /**
     * Converts the array of opaque android.os.Temperature parcelables returned by
     * IThermalService into the "Temperature{mValue=.., mType=.., mName=..}" line format
     * ThermalMonitor's parser already understands (matching dumpsys's own output shape).
     * Tries public getters first, falling back to declared-field reflection for OEM
     * builds whose Temperature class lacks them.
     */
    private fun parseTemperatureObjects(temps: Array<*>): String? {
        val builder = StringBuilder()
        var hasValidData = false
        for (temp in temps) {
            if (temp == null) continue
            var name = runCatching { temp.javaClass.getMethod("getName").invoke(temp) as? String }.getOrNull() ?: ""
            var value = runCatching { (temp.javaClass.getMethod("getValue").invoke(temp) as? Number)?.toFloat() }.getOrNull() ?: 0f
            var type = runCatching { (temp.javaClass.getMethod("getType").invoke(temp) as? Number)?.toInt() }.getOrNull() ?: 0

            if (value == 0f && name.isBlank()) {
                for (field in temp.javaClass.declaredFields) {
                    field.isAccessible = true
                    when (field.name.lowercase()) {
                        "mname", "name" -> name = field.get(temp) as? String ?: ""
                        "mvalue", "value" -> value = (field.get(temp) as? Number)?.toFloat() ?: 0f
                        "mtype", "type" -> type = (field.get(temp) as? Number)?.toInt() ?: 0
                    }
                }
            }

            if (value > 0f || name.isNotBlank()) hasValidData = true
            builder.append("Temperature{mValue=").append(value)
                .append(", mType=").append(type)
                .append(", mName=").append(name).append("}\n")
        }
        return if (hasValidData && builder.isNotEmpty()) builder.toString() else null
    }

    /**
     * Reads via `dumpsys thermalservice`. Returns null when the dump contains no
     * recognizable sensor data, so callers can fall through to the sysfs strategy.
     * [requireValid] = false bypasses that check for the final "give the caller something
     * to parse-fail on" fallback in [getThermalTemperatures].
     */
    private fun readViaDumpsys(requireValid: Boolean = true): String? {
        val dump = executeCommand("dumpsys thermalservice")
        if (!requireValid) return dump
        return if (dump.contains("Temperature{") || dump.contains("mValue=")) dump else null
    }

    /**
     * Reads raw zone temperatures directly from /sys/class/thermal on devices where
     * neither the IThermalService reflection path nor dumpsys thermalservice expose any
     * sensor data (HAL not ready / legacy Qualcomm builds, e.g. Galaxy Tab A Lite).
     * This is the most expensive strategy (shell + a loop over every thermal zone), which
     * is exactly why it is only ever invoked once discovery has resolved it as the working
     * strategy for this device, rather than re-attempted every poll tick.
     */
    private fun readViaSysfs(): String? {
        val sysfsDump = executeCommand(
            "sh -c 'for z in /sys/class/thermal/thermal_zone*; do echo \"\$(cat \$z/type 2>/dev/null):\$(cat \$z/temp 2>/dev/null)\"; done'"
        )
        val hasValidLine = sysfsDump.lines().any {
            it.contains(":") && it.substringBefore(":").isNotBlank() && it.substringAfter(":").isNotBlank()
        }
        return if (sysfsDump.isNotBlank() && hasValidLine) sysfsDump else null
    }

    override fun suspendPackages(packageNames: Array<out String>?, suspended: Boolean): Int {
        if (packageNames.isNullOrEmpty()) return 0
        var successCount = 0
        try {
            val pm = context.packageManager
            val method = pm.javaClass.getMethod(
                "setPackagesSuspended",
                Array<String>::class.java,
                Boolean::class.javaPrimitiveType,
                android.os.PersistableBundle::class.java,
                android.os.PersistableBundle::class.java,
                String::class.java
            )
            val unfailed = method.invoke(pm, packageNames, suspended, null, null, "com.framex.app") as? Array<*>
            successCount = packageNames.size - (unfailed?.size ?: 0)
        } catch (e: Exception) {
            for (pkg in packageNames) {
                val cmd = if (suspended) "pm suspend --user 0 $pkg" else "pm unsuspend --user 0 $pkg"
                executeCommand(cmd)
                successCount++
            }
        }
        return successCount
    }

    override fun setAppOpMode(packageNames: Array<out String>?, opCode: Int, mode: Int): Int {
        if (packageNames.isNullOrEmpty()) return 0
        var count = 0
        val modeStr = when (mode) {
            1 -> "ignore"
            2 -> "deny"
            else -> "allow"
        }
        for (pkg in packageNames) {
            executeCommand("cmd appops set $pkg $opCode $modeStr")
            count++
        }
        return count
    }

    override fun destroy() {
        exitProcess(0)
    }
}
