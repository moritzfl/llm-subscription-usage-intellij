package de.moritzf.quota.idea.kimi

import com.intellij.ide.util.PropertiesComponent
import de.moritzf.quota.idea.common.rethrowIfCancellation
import de.moritzf.quota.kimi.KimiDeviceHeaders

/** One plugin-owned device identity shared by login, refresh, quota, MCP, and proxy clients. */
internal class KimiDeviceIdStore(
    private val read: () -> String? = { PropertiesComponent.getInstance().getValue(KEY_DEVICE_ID) },
    private val write: (String) -> Unit = {
        PropertiesComponent.getInstance().setValue(KEY_DEVICE_ID, it)
    },
) {
    val deviceId: String by lazy {
        try {
            read()?.takeIf { it.isNotBlank() } ?: KimiDeviceHeaders.processDeviceId.also(write)
        } catch (failure: Exception) {
            failure.rethrowIfCancellation()
            KimiDeviceHeaders.processDeviceId
        }
    }

    companion object {
        private const val KEY_DEVICE_ID = "kimi.oauth.device.id"
        private val shared = KimiDeviceIdStore()

        fun get(): String = shared.deviceId
    }
}
