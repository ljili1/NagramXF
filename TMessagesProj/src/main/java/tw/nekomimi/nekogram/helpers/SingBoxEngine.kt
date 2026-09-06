package tw.nekomimi.nekogram.helpers

import android.content.Context
import org.telegram.messenger.FileLog
import java.io.File

/**
 * Runs the bundled sing-box binary that powers the built-in VLESS proxy.
 *
 * The binary is shipped as a native library named `libsingbox.so` under
 * `TMessagesProj/src/main/jniLibs/arm64-v8a/`, so Android extracts it into the
 * app's native library directory — which, unlike filesDir, is executable.
 * (The manifest sets android:extractNativeLibs="true" to guarantee extraction.)
 *
 * This avoids depending on the `libbox` gomobile AAR, whose PlatformInterface
 * spans ~29 generated methods and is brittle to bind against.
 */
object SingBoxEngine {
    private const val LIB_NAME = "libsingbox.so"
    private const val CONFIG_NAME = "sing-box-vless.json"

    private var process: Process? = null

    @Synchronized
    fun start(context: Context, configJson: String): Boolean {
        stop()
        return try {
            val binary = File(context.applicationInfo.nativeLibraryDir, LIB_NAME)
            if (!binary.exists()) {
                FileLog.e("SingBoxEngine: sing-box binary not found at ${binary.absolutePath}")
                return false
            }
            if (!binary.canExecute()) {
                FileLog.e("SingBoxEngine: sing-box binary is not executable: ${binary.absolutePath}")
                return false
            }

            val configFile = File(context.filesDir, CONFIG_NAME)
            configFile.writeText(configJson)

            process = ProcessBuilder(binary.absolutePath, "run", "-c", configFile.absolutePath)
                .apply {
                    redirectErrorStream(true)
                    environment()["HOME"] = context.filesDir.absolutePath
                    environment()["TMPDIR"] = context.cacheDir.absolutePath
                }
                .start()
            FileLog.d("SingBoxEngine: started sing-box (pid via process object) config=${configFile.absolutePath}")
            true
        } catch (e: Throwable) {
            FileLog.e(e)
            false
        }
    }

    @Synchronized
    fun stop() {
        try {
            process?.destroy()
        } catch (e: Throwable) {
            FileLog.e(e)
        } finally {
            process = null
        }
    }

    @Synchronized
    fun isRunning(): Boolean {
        return try {
            process?.isAlive == true
        } catch (e: Throwable) {
            false
        }
    }
}
