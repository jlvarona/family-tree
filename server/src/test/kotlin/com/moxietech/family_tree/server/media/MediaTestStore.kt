package com.moxietech.family_tree.server.media

import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import java.time.Duration
import java.util.UUID

/**
 * FT-008: a disposable S3-compatible object store for the media tests, on loopback
 * only. SeaweedFS (`weed mini`) checks SigV4 signatures on presigned URLs, denies
 * anonymous access to a private bucket and implements bucket CORS through the S3 API,
 * so uploads, downloads and the browser preflight are exercised for real.
 *
 * The credentials are random per run (nothing in the repository). `S3_BUCKET` makes
 * the container create the private bucket; [S3ObjectStore.ensureBucket] is idempotent
 * on top. Override the image with `-PobjectStoreImage=<image:tag>`.
 */
internal class MediaTestStore : AutoCloseable {

    val accessKey = "ft008-" + UUID.randomUUID().toString().take(8)
    val secretKey = UUID.randomUUID().toString().replace("-", "")
    val bucket = "ft008-media"

    val container: GenericContainer<*> = GenericContainer(DockerImageName.parse(IMAGE))
        .withCommand("mini", "-dir=/data")
        .withEnv("AWS_ACCESS_KEY_ID", accessKey)
        .withEnv("AWS_SECRET_ACCESS_KEY", secretKey)
        .withEnv("S3_BUCKET", bucket)
        .withExposedPorts(S3_PORT)
        // Published on loopback only, on a random host port.
        .withCreateContainerCmdModifier { cmd ->
            cmd.hostConfig?.withPortBindings(PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(S3_PORT)))
        }
        .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)))

    lateinit var endpoint: String
        private set
    lateinit var settings: MediaSettings
        private set

    fun start(): MediaTestStore {
        container.start()
        endpoint = "http://${container.host}:${container.getMappedPort(S3_PORT)}"
        settings = MediaSettings(
            bucket = bucket,
            region = "us-east-1",
            endpoint = endpoint,
            publicEndpoint = endpoint,
            pathStyle = true,
            createBucket = false,
            localCorsOrigins = emptyList(),
        )
        // weed mini starts its master, volume, filer and S3 gateway in turn; wait until
        // the bucket answers through the S3 API with these credentials.
        runBlocking {
            val store = newStore()
            var lastError: Exception? = null
            repeat(60) {
                try {
                    store.ensureBucket()
                    store.close()
                    return@runBlocking
                } catch (e: Exception) {
                    lastError = e
                    delay(1_000)
                }
            }
            store.close()
            throw IllegalStateException("The S3-compatible test store didn't become ready", lastError)
        }
        return this
    }

    fun newStore(): S3ObjectStore =
        S3ObjectStore(settings, StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))

    override fun close() = container.stop()

    companion object {
        /** Verified by the reviewer as an official, pullable image (FT-008). */
        const val DEFAULT_IMAGE = "ghcr.io/chrislusf/seaweedfs:4.54"
        val IMAGE: String = System.getProperty("family_tree.objectStoreImage")?.takeIf { it.isNotBlank() } ?: DEFAULT_IMAGE
        const val S3_PORT = 8333
    }
}
