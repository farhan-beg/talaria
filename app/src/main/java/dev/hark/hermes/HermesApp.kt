package dev.hark.hermes

import kotlinx.coroutines.launch

import android.app.Application
import dev.hark.hermes.data.Api
import dev.hark.hermes.data.Gateway
import dev.hark.hermes.data.NativeAuth
import dev.hark.hermes.data.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

class HermesApp : Application() {
    lateinit var store: Store; private set
    lateinit var api: Api; private set
    lateinit var auth: NativeAuth; private set
    val scope = kotlinx.coroutines.MainScope()

    /**
     * One live connection per saved server, like the desktop's connection registry. Switching servers no longer
     * tears the old socket down: a chat still running there keeps streaming, and its asks and "reply ready"
     * notification route back to it.
     */
    private val gateways = ConcurrentHashMap<String, Gateway>()
    private lateinit var _active: MutableStateFlow<Gateway>
    val activeGateway: StateFlow<Gateway> get() = _active
    /** The connection for the server you're looking at. */
    val gateway: Gateway get() = _active.value

    private val bg by lazy { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main) }

    fun gatewayFor(server: String): Gateway = gateways.getOrPut(server) {
        Gateway(api, store, server).also { g ->
            g.watchNetwork(this)
            bg.launch { g.busy.collect { if (it && g.isActive) TurnService.start(this@HermesApp) } }
            bg.launch {
                g.lastOutcome.collect { o ->
                    if (o == null) return@collect
                    // a chat on another server is never on screen, so it always pings
                    if (!g.isActive) TurnService.replyReady(this@HermesApp, store.serverLabel(server) + " · " + g.title.value, o.status, o.text, server, g.storedSid)
                    else if (!foreground) TurnService.replyReady(this@HermesApp, g.title.value, o.status, o.text, server, g.storedSid)
                }
            }
            bg.launch {
                g.backgroundDone.collect { (q, t) ->
                    if (!foreground || !g.isActive) TurnService.backgroundDone(this@HermesApp, q, t, server, g.storedSid)
                }
            }
        }
    }

    /** Forget a server's connection entirely (removed, or signed out of). */
    fun dropGateway(server: String) {
        val g = gateways[server] ?: return
        g.reset()
        if (g !== _active.value) gateways.remove(server)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        api = Api(store)
        auth = NativeAuth(api, store)
        TurnService.ensureChannels(this)
        _active = MutableStateFlow(gatewayFor(store.activeId.value))
        bg.launch {
            store.activeId.collect { id ->
                val old = _active.value
                if (old.serverId == id) return@collect
                old.deactivate()
                // the server you left keeps its socket and its open chat: a turn there keeps streaming and pings when done
                val next = gatewayFor(id)
                next.activate()
                _active.value = next
            }
        }
    }

    /** True while an activity is on screen; "reply ready" only pings when you're elsewhere. */
    @Volatile var foreground = false

    companion object { lateinit var instance: HermesApp; private set }
}

val app get() = HermesApp.instance
