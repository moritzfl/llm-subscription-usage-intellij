package de.moritzf.quota.shared

/** Deliberate dependency violations, imported only by architecture rule self-tests. */
class ArchitectureSharedFixtures {
    class Provider(val client: de.moritzf.quota.kimi.KimiQuotaClient)

    class Proxy(val store: de.moritzf.proxy.server.ApiKeyStore)

    class Valid(val pcm: RealtimeSpeechSession.Pcm, val path: java.nio.file.Path)
}
