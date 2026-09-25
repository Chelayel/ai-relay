package com.chelayel.airelay.cli

import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS trust that matches what the rest of the machine trusts. The bundled Java
 * runtime ships its own certificate store, which knows nothing about a
 * corporate proxy's root certificate — so behind one, every HTTPS call
 * (Apigee, a fetched page, Maven Central, the update check) died with
 * `PKIX path building failed` while the browser next to it was fine.
 *
 * Installed once at startup: a trust manager that accepts a chain when any of
 * these does — Java's own store, the operating system's store (Windows-ROOT,
 * the macOS keychain), and any PEM/CRT files in `~/.airelay/certs/` or named
 * by `AIRELAY_CA_BUNDLE` / `SSL_CERT_FILE` / `ssl.ca.bundle`.
 */
object Trust {

    /** What was added on top of Java's own store, for the banner and `airelay web`. */
    @Volatile var sources: List<String> = emptyList()
        private set

    fun install(configBundle: String? = null) {
        runCatching {
            val managers = mutableListOf<X509TrustManager>()
            val names = mutableListOf<String>()
            // Without Java's own store nothing public would verify; if it cannot be read, leave the JVM default alone.
            val default = defaultManager() ?: return
            managers.add(default)
            osStore()?.let { (name, tm) -> managers.add(tm); names.add(name) }
            val pems = extraCertificates(configBundle)
            if (pems.second.isNotEmpty()) { managers.add(pems.second.toManager()); names.addAll(pems.first) }
            if (managers.size <= 1 && names.isEmpty()) return
            val composite = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = managers.flatMap { it.acceptedIssuers.toList() }.toTypedArray()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = check { it.checkClientTrusted(chain, authType) }
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = check { it.checkServerTrusted(chain, authType) }
                private fun check(f: (X509TrustManager) -> Unit) {
                    var last: CertificateException? = null
                    for (m in managers) try { f(m); return } catch (e: CertificateException) { last = e }
                    throw last ?: CertificateException("No trust manager")
                }
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(composite), null)
            SSLContext.setDefault(ctx)
            sources = names
        }
    }

    private fun defaultManager(): X509TrustManager? = runCatching {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    }.getOrNull()

    private fun osStore(): Pair<String, X509TrustManager>? {
        val os = System.getProperty("os.name", "")
        val type = when {
            os.startsWith("Windows") -> "Windows-ROOT"
            os.startsWith("Mac") -> "KeychainStore"
            else -> return null
        }
        return runCatching {
            val ks = KeyStore.getInstance(type).apply { load(null, null) }
            val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
                .trustManagers.filterIsInstance<X509TrustManager>().first()
            if (tm.acceptedIssuers.isEmpty()) null else "$type (${tm.acceptedIssuers.size} roots)" to tm
        }.getOrNull()
    }

    /** PEM/DER certificate files: named sources, and the certificates read from them. */
    private fun extraCertificates(configBundle: String?): Pair<List<String>, List<X509Certificate>> {
        val files = mutableListOf<File>()
        listOfNotNull(System.getenv("AIRELAY_CA_BUNDLE"), System.getenv("SSL_CERT_FILE"), configBundle)
            .filter { it.isNotBlank() }.map(::File).filter { it.isFile }.forEach { files.add(it) }
        File(System.getProperty("user.home") ?: ".", ".airelay/certs").listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("pem", "crt", "cer", "der") }?.sorted()?.forEach { files.add(it) }
        val factory = CertificateFactory.getInstance("X.509")
        val certs = mutableListOf<X509Certificate>()
        val names = mutableListOf<String>()
        for (f in files.distinctBy { it.canonicalPath }) {
            val read = runCatching { f.inputStream().use { factory.generateCertificates(it) }.filterIsInstance<X509Certificate>() }.getOrDefault(emptyList())
            if (read.isNotEmpty()) { certs.addAll(read); names.add("${f.name} (${read.size})") }
        }
        return names to certs
    }

    private fun List<X509Certificate>.toManager(): X509TrustManager {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        forEachIndexed { i, c -> ks.setCertificateEntry("extra-$i", c) }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
