package de.moritzf.quota.openai.proxy.pdf

import javax.imageio.ImageIO
import javax.imageio.spi.IIORegistry
import javax.imageio.spi.ImageReaderSpi

/**
 * ImageIO service providers live in the plugin classloader. The JDK registry is initialized from
 * the IDE's loader, so JPEG2000/JBIG2 jars on the plugin classpath are invisible until we register
 * them ourselves.
 */
internal object PdfImageIoPlugins {
    private val lock = Any()

    @Volatile private var registered = false

    fun ensureRegistered() {
        if (registered) return
        synchronized(lock) {
            if (registered) return
            val pluginLoader = PdfImageIoPlugins::class.java.classLoader
            val registry = IIORegistry.getDefaultInstance()
            registerReader(
                registry,
                pluginLoader,
                "com.github.jaiimageio.jpeg2000.impl.J2KImageReaderSpi",
            )
            registerReader(registry, pluginLoader, "org.apache.pdfbox.jbig2.JBIG2ImageReaderSpi")
            val previous = Thread.currentThread().contextClassLoader
            try {
                Thread.currentThread().contextClassLoader = pluginLoader
                ImageIO.scanForPlugins()
            } finally {
                Thread.currentThread().contextClassLoader = previous
            }
            registered = true
        }
    }

    private fun registerReader(registry: IIORegistry, loader: ClassLoader, className: String) {
        val spi =
            runCatching {
                loader.loadClass(className).getDeclaredConstructor().newInstance() as ImageReaderSpi
            }
                .getOrNull() ?: return
        registry.registerServiceProvider(spi)
    }
}
