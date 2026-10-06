package dev.hark.hermes

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
    }

    companion object { lateinit var instance: HermesApp; private set }
}

val app get() = HermesApp.instance
