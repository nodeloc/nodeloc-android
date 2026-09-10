package com.nodeloc.app.core.util

import android.annotation.SuppressLint
import android.os.Build

/**
 * What this handset would call itself, for the tail on a post.
 *
 * Raw values only. The translation from `V2559UA` to `iQOO Z11i` happens on the
 * server, so a wrong name costs a table edit rather than a release — and the
 * iOS app, which cannot read a marketing name at all, needs that table anyway.
 */
object DeviceInfo {

    const val PLATFORM = "android"

    /** `Build.MANUFACTURER`, as the ROM writes it — usually lower case. */
    val brand: String get() = Build.MANUFACTURER.orEmpty().trim()

    /**
     * `Build.MODEL`, which on most Chinese ROMs is an internal code: this
     * handset answers `V2559UA` for a phone sold as an iQOO Z11i. Sent anyway,
     * because it is the key the server's table is keyed on.
     */
    val model: String get() = Build.MODEL.orEmpty().trim()

    /**
     * The name on the box, where the ROM is willing to say.
     *
     * There is no public API for this — Android has never had one — so it comes
     * out of the vendor's own system properties through the reflection everyone
     * uses, and returns null the moment that stops working. Which is fine: the
     * server falls back to its table, and then to the brand. Nothing here is
     * load-bearing enough to deserve a hidden-API fight.
     */
    val marketingName: String?
        get() = MARKETING_NAME_KEYS.firstNotNullOfOrNull { key ->
            systemProperty(key)?.takeIf { it.isNotBlank() }
        }

    /** Every vendor put it somewhere else, and none of them wrote it down. */
    private val MARKETING_NAME_KEYS = listOf(
        "ro.vivo.market.name",              // vivo, iQOO
        "ro.product.marketname",            // Xiaomi, Redmi, POCO
        "ro.config.marketing_name",         // HUAWEI, HONOR
        "ro.vendor.oplus.market.name",      // OPPO, OnePlus, realme — newer
        "ro.oppo.market.name",              // the same, older
        "ro.product.vendor.marketname",
    )

    // Lint is right that this is a private API, and it is allowed anyway: the
    // call is wrapped, its failure is a supported outcome rather than an
    // error path, and the alternative is showing people `V2559UA` where their
    // phone says iQOO Z11i. Nothing breaks the day a ROM closes it — the tail
    // falls back to the brand, which is a rung the reader already agreed to.
    @SuppressLint("PrivateApi")
    private fun systemProperty(key: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        get.invoke(null, key) as? String
    }.getOrNull()

    /**
     * The brand its owner would name, which is not always `Build.MANUFACTURER`.
     *
     * This handset reports `vivo` and is an iQOO; Redmi and POCO sit under
     * Xiaomi the same way. The marketing name is where the sub-brand shows, so
     * its first word decides — and the server still has the last word on
     * spelling, because "iqoo" is not how anyone writes it.
     *
     * The one derivation the client does rather than the server, and only
     * because of the rung below: at brand level the marketing name must not
     * leave the device, so the server cannot be the one to read it.
     */
    val displayBrand: String
        get() {
            val first = marketingName?.trim()?.substringBefore(' ')?.lowercase()
            return if (first != null && first in SUB_BRANDS) first else brand
        }

    private val SUB_BRANDS = setOf("iqoo", "redmi", "poco", "realme", "honor", "nothing")

    /**
     * The fields a post carries, trimmed to the rung the author chose.
     *
     * Trimmed here rather than server-side on purpose. The server would discard
     * what it is not allowed to store, but not sending it at all is the same
     * bargain the storage rule already makes: someone who agreed to show a
     * brand has not agreed to hand over their exact model on every post they
     * write, even to a server that promises to drop it.
     */
    fun postSourceFields(level: Int): Map<String, String> {
        if (level <= 0) return emptyMap()
        val fields = mutableMapOf("mobile_source_platform" to PLATFORM)
        if (level >= 3) displayBrand.takeIf { it.isNotEmpty() }?.let { fields["mobile_source_brand"] = it }
        if (level >= 4) {
            model.takeIf { it.isNotEmpty() }?.let { fields["mobile_source_model"] = it }
            marketingName?.let { fields["mobile_source_marketing_name"] = it }
        }
        return fields
    }
}
