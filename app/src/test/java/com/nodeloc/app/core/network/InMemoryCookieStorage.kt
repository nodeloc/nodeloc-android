package com.nodeloc.app.core.network

/** The jar's persistence without a device behind it. */
class InMemoryCookieStorage(private var value: String? = null) : CookieStorage {
    override fun read(): String? = value
    override fun write(value: String?) {
        this.value = value
    }
}
