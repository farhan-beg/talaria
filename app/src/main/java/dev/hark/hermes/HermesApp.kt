package dev.hark.hermes

import kotlinx.coroutines.launch

import android.app.Application
import dev.hark.hermes.data.Api
import dev.hark.hermes.data.Gateway
import dev.hark.hermes.data.NativeAuth
import dev.hark.hermes.data.Store

class HermesApp : Application() {
    lateinit var store: Store; private set
    lateinit var api: Api; private set
    lateinit var auth: NativeAuth; private set
    lateinit var gateway: Gateway; private set
    val scope = kotlinx.coroutines.MainScope()

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        api = Api(store)
        auth = NativeAuth(api, store)
        gateway = Gateway(api, store)
        gateway.watchNetwork(this)
        TurnService.ensureChannels(this)
        val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
        bg.launch { gateway.busy.collect { if (it) TurnService.start(this@HermesApp) } }
        bg.launch {
            gateway.lastOutcome.collect { o ->
                if (o != null && !foreground) TurnService.replyReady(this@HermesApp, gateway.title.value, o.status, o.text)
            }
        }
    }

    /** True while an activity is on screen; "reply ready" only pings when you're elsewhere. */
    @Volatile var foreground = false

    companion object { lateinit var instance: HermesApp; private set }
}

val app get() = HermesApp.instance
