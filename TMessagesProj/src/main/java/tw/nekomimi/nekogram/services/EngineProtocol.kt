package tw.nekomimi.nekogram.services

/** Messenger protocol between the main process and the engine process. */
object EngineProtocol {
    const val MSG_START = 1
    const val MSG_STOP = 2
    const val MSG_STATUS = 3

    const val REPLY_OK = 100
    const val REPLY_ERR = 101

    const val KEY_ID = "id"
    const val KEY_LINK = "link"
    const val KEY_PORT_HINT = "portHint"
    const val KEY_PORT = "port"
    const val KEY_ERROR = "error"
    const val KEY_RUNNING = "running"
}
