package tw.nekomimi.nekogram.services

/**
 * Throwaway sing-box engine host used exclusively by the proxy connectivity
 * tester ("check this node / retest ping / auto-select candidates").
 *
 * It is the exact same engine as [SingBoxEngineService] — only the process
 * differs (`:singbox_test`). Why a second process instead of reusing the live
 * one:
 *
 *  * the live engine holds the single node Telegram is connected through.
 *    Probing any other node with that engine requires stopping the live node
 *    first, which kills every connection for the whole test window (the
 *    "proxy cannot connect / reconnects several times in a few seconds"
 *    symptom). A throwaway engine leaves the live one untouched.
 *  * test nodes come straight from pasted / subscribed links, i.e. exactly the
 *    input most likely to make libbox abort natively. With a dedicated process
 *    such a crash is contained to a process nobody depends on; the live engine
 *    and the UI keep running.
 *
 * The main process drives it through
 * [tw.nekomimi.nekogram.helpers.ProxyTestEngineClient], which binds the service,
 * sends the usual [EngineProtocol] START/STOP commands and releases it once the
 * test queue has drained.
 */
class SingBoxTestEngineService : SingBoxEngineService()
