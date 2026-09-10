package com.nodeloc.app

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import okio.Path.Companion.toOkioPath

class NodelocApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        val services = ServiceLocator.init(this)
        services.authService.restore()

        // An open long-poll is not free to hold. Now that one bus serves the
        // whole app rather than dying with a conversation screen, something has
        // to stop it when the app is not on screen.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> services.messageBus.setAppForeground(true)
                    Lifecycle.Event.ON_STOP -> services.messageBus.setAppForeground(false)
                    else -> Unit
                }
            },
        )
    }

    /**
     * One image loader for the app, sharing the OkHttp stack (and therefore the
     * cookie jar) so avatars and uploads behind a session load too. Memory and
     * disk caches replace the hand-rolled two-level cache the iOS build needed.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { ServiceLocator.get.client.http }))
                // AnimatedImageDecoder is API 28+; GifDecoder covers 26–27.
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(SvgDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder().maxSizePercent(context, 0.22).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(160L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}
