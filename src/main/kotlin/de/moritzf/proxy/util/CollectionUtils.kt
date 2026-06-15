package de.moritzf.proxy.util

object CollectionUtils {
    @JvmStatic
    fun uniqueStrings(values: List<String>?): List<String> {
        return values?.let { ArrayList(LinkedHashSet(it)) }.orEmpty()
    }
}
