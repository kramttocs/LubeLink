package com.kramttocs.lubelink.quality

import android.app.Application
import androidx.work.Configuration
import android.security.keystore.KeyGenParameterSpec
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * The Application Robolectric starts for JVM tests (robolectric.properties). Robolectric has no Android Keystore, so
 * this installs a software stand-in named "AndroidKeyStore": keys live in memory for the test JVM only. The app's
 * SecureConfig then runs unchanged. On the emulator the real Keystore is used.
 */
class JvmTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Security.getProvider(NAME) == null) Security.addProvider(Stand_in())
        // The app starts WorkManager on demand (its initializer is disabled in the manifest); give the JVM run one.
        // WorkManager's test setup, because Robolectric does not run the platform job scheduler that WorkManager
        // uses for work with constraints: a test releases a job's constraints through its test driver.
        runCatching {
            androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(this,
                Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).build())
        }
    }

    @Suppress("ClassName")
    private class Stand_in : Provider(NAME, 1.0, "In-memory Android Keystore stand-in for JVM tests") {
        init {
            put("KeyStore.$NAME", Store::class.java.name)
            put("KeyGenerator.AES", Generator::class.java.name)
        }
    }

    class Store : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCreationDate(alias: String): Date? = if (keys.containsKey(alias)) Date(0) else null
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<Certificate>?) {
            keys[alias] = key as SecretKey
        }
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<Certificate>?) = throw UnsupportedOperationException()
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = throw UnsupportedOperationException()
        override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())
        override fun engineContainsAlias(alias: String) = keys.containsKey(alias)
        override fun engineSize() = keys.size
        override fun engineIsKeyEntry(alias: String) = keys.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String) = false
        override fun engineGetCertificateAlias(cert: Certificate): String? = null
        override fun engineStore(stream: java.io.OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: java.io.InputStream?, password: CharArray?) = Unit
    }

    class Generator : KeyGeneratorSpi() {
        private var alias = ""
        private var bits = 256
        override fun engineInit(random: SecureRandom?) = Unit
        override fun engineInit(params: AlgorithmParameterSpec, random: SecureRandom?) {
            val spec = params as KeyGenParameterSpec
            alias = spec.keystoreAlias
            bits = spec.keySize.takeIf { it > 0 } ?: 256
        }
        override fun engineInit(keysize: Int, random: SecureRandom?) { bits = keysize }
        override fun engineGenerateKey(): SecretKey =
            SecretKeySpec(ByteArray(bits / 8).also { SecureRandom().nextBytes(it) }, "AES").also { keys[alias] = it }
    }

    companion object {
        private const val NAME = "AndroidKeyStore"
        private val keys = ConcurrentHashMap<String, SecretKey>()
    }
}
