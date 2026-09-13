package de.moritzf.proxy.server

import de.moritzf.proxy.usage.UsageTracker
import io.ktor.util.AttributeKey

object ProxyCallAttributes {
    val IS_ADMIN = AttributeKey<Boolean>("isAdmin")
    val ADMIN_KEY_FINGERPRINT = AttributeKey<String>("adminKeyFingerprint")
    val KEY_NAME = AttributeKey<String>("keyName")
    val KEY_FINGERPRINT = AttributeKey<String>("keyFingerprint")
    val USAGE_TRACKER = AttributeKey<UsageTracker>("usageTracker")
}
