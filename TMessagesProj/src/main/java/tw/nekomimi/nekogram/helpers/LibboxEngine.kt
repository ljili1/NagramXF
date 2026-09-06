package tw.nekomimi.nekogram.helpers

import android.content.Context
import org.telegram.messenger.FileLog

/**
 * Thin wrapper around the sing-box `libbox` AAR.
 *
 * IMPORTANT — verify against your bundled AAR:
 * The symbols below assume the upstream `SagerNet/sing-box` `experimental/libbox`
 * build produced with:
 *     gomobile bind -javapkg=io.nekohasekai -libname=box ./experimental/libbox
 * i.e. the Java package is `io.nekohasekai` and the native lib is `libbox.so`.
 *
 * If your prebuilt AAR uses a different Java package (e.g. `libbox`), change the
 * imports/package below. Method names may also differ by sing-box version; open
 * the AAR's classes.jar with `javap` to confirm:
 *     unzip -p libbox.aar classes.jar > classes.jar && javap -p classes.jar
 *
 * [start] returns an opaque service handle (the libbox Service object), [stop]
 * closes it. Both are wrapped so a mismatch fails soft instead of crashing.
 */
object LibboxEngine {

    fun start(context: Context, configJson: String): Any? {
        return try {
            val setup = io.nekohasekai.SetupOptions()
            setup.setBasePath(context.filesDir.absolutePath)
            setup.setWorkingPath(context.filesDir.absolutePath)
            setup.setTempPath(context.cacheDir.absolutePath)
            io.nekohasekai.Setup(setup)

            // Returns a Service handle (type depends on the AAR version).
            io.nekohasekai.StartService(configJson)
        } catch (e: Throwable) {
            FileLog.e(e)
            null
        }
    }

    fun stop(service: Any?) {
        if (service == null) return
        try {
            val close = service.javaClass.getMethod("Close")
            close.invoke(service)
        } catch (e: Throwable) {
            try {
                val close = service.javaClass.getMethod("close")
                close.invoke(service)
            } catch (e2: Throwable) {
                FileLog.e(e2)
            }
        }
    }
}
