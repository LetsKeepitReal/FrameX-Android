package com.framex.app.shizuku

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

class CommandRunnerService(private val context: Context) : ICommandRunner.Stub() {

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
        return try {
            com.framex.app.utils.FrameXLog.d("getThermalTemperatures: starting reflection", "CmdRunner")
            val binderClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = binderClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "thermalservice") as? android.os.IBinder
            if (binder != null) {
                val stubClass = Class.forName("android.os.IThermalService\$Stub")
                val asInterfaceMethod = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
                val service = asInterfaceMethod.invoke(null, binder)
                if (service != null) {
                    val availableMethods = service.javaClass.methods.filter { 
                        it.name.contains("Temperature", ignoreCase = true) 
                    }
                    com.framex.app.utils.FrameXLog.d("Available IThermalService methods: ${availableMethods.map { "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})" }}", "CmdRunner")

                    var temps: Array<*>? = null
                    for (m in availableMethods) {
                        try {
                            val result = when (m.parameterTypes.size) {
                                0 -> m.invoke(service)
                                1 -> {
                                    val p0 = m.parameterTypes[0]
                                    if (p0 == Boolean::class.javaPrimitiveType || p0 == Boolean::class.java) m.invoke(service, false)
                                    else m.invoke(service, 0)
                                }
                                2 -> {
                                    val p0 = m.parameterTypes[0]
                                    val p1 = m.parameterTypes[1]
                                    if (p0 == Boolean::class.javaPrimitiveType && p1 == Int::class.javaPrimitiveType) {
                                        m.invoke(service, false, 0)
                                    } else if (p0 == Int::class.javaPrimitiveType && p1 == Boolean::class.javaPrimitiveType) {
                                        m.invoke(service, 0, false)
                                    } else {
                                        m.invoke(service, false, 0)
                                    }
                                }
                                else -> null
                            }
                            if (result is Array<*> && result.isNotEmpty()) {
                                temps = result
                                com.framex.app.utils.FrameXLog.d("Successfully invoked ${m.name} with ${m.parameterTypes.size} args, returned ${result.size} items", "CmdRunner")
                                break
                            } else if (result is List<*> && result.isNotEmpty()) {
                                temps = result.toTypedArray()
                                com.framex.app.utils.FrameXLog.d("Successfully invoked ${m.name} with ${m.parameterTypes.size} args, returned ${result.size} list items", "CmdRunner")
                                break
                            }
                        } catch (e: Exception) {
                            com.framex.app.utils.FrameXLog.w("Failed invoking ${m.name}: ${e.message}", null, "CmdRunner")
                        }
                    }

                    if (temps != null && temps.isNotEmpty()) {
                        val sb = StringBuilder()
                        var hasValidData = false
                        for ((i, temp) in temps.withIndex()) {
                            if (temp != null) {
                                var name = runCatching { temp.javaClass.getMethod("getName").invoke(temp) as? String }.getOrNull() ?: ""
                                var value = runCatching { (temp.javaClass.getMethod("getValue").invoke(temp) as? Number)?.toFloat() }.getOrNull() ?: 0f
                                var type = runCatching { (temp.javaClass.getMethod("getType").invoke(temp) as? Number)?.toInt() }.getOrNull() ?: 0

                                if (value == 0f && name.isBlank()) {
                                    val fields = temp.javaClass.declaredFields
                                    for (f in fields) {
                                        f.isAccessible = true
                                        when (f.name.lowercase()) {
                                            "mname", "name" -> name = f.get(temp) as? String ?: ""
                                            "mvalue", "value" -> value = (f.get(temp) as? Number)?.toFloat() ?: 0f
                                            "mtype", "type" -> type = (f.get(temp) as? Number)?.toInt() ?: 0
                                        }
                                    }
                                }

                                if (value > 0f || name.isNotBlank()) {
                                    hasValidData = true
                                }
                                sb.append("Temperature{mValue=").append(value)
                                  .append(", mType=").append(type)
                                  .append(", mName=").append(name).append("}\n")
                            }
                        }
                        com.framex.app.utils.FrameXLog.d("hasValidData=$hasValidData, sbLen=${sb.length}", "CmdRunner")
                        if (hasValidData && sb.isNotEmpty()) return sb.toString()
                    }
                }
            }
            com.framex.app.utils.FrameXLog.d("reflection failed, trying dumpsys", "CmdRunner")
            val dump = executeCommand("dumpsys thermalservice")
            if (dump.contains("Temperature{") || dump.contains("mValue=")) {
                return dump
            }
            val sysfsDump = executeCommand("sh -c 'for z in /sys/class/thermal/thermal_zone*; do echo \"\$(cat \$z/type 2>/dev/null):\$(cat \$z/temp 2>/dev/null)\"; done'")
            if (sysfsDump.isNotBlank() && sysfsDump.lines().any { it.contains(":") && it.substringBefore(":").isNotBlank() && it.substringAfter(":").isNotBlank() }) {
                return sysfsDump
            }
            return dump
        } catch (e: Exception) {
            com.framex.app.utils.FrameXLog.e("getThermalTemperatures exception", e, "CmdRunner")
            val dump = executeCommand("dumpsys thermalservice")
            if (dump.contains("Temperature{") || dump.contains("mValue=")) {
                return dump
            }
            val sysfsDump = executeCommand("sh -c 'for z in /sys/class/thermal/thermal_zone*; do echo \"\$(cat \$z/type 2>/dev/null):\$(cat \$z/temp 2>/dev/null)\"; done'")
            if (sysfsDump.isNotBlank() && sysfsDump.lines().any { it.contains(":") && it.substringBefore(":").isNotBlank() && it.substringAfter(":").isNotBlank() }) sysfsDump else dump
        }
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
