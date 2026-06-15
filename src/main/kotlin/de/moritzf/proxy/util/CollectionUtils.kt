package de.moritzf.proxy.util
object CollectionUtils {
    fun uniqueStrings(values: List<String>?): List<String> {
        return values?.let { ArrayList(LinkedHashSet(it)) }.orEmpty()
    }
}
