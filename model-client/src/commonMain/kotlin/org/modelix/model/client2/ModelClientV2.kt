/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modelix.model.client2

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.modelix.model.IVersion
import org.modelix.model.api.IIdGenerator
import org.modelix.model.api.IdGeneratorDummy
import org.modelix.model.client.IdGenerator
import org.modelix.model.lazy.BranchReference
import org.modelix.model.lazy.CLVersion
import org.modelix.model.lazy.ObjectStoreCache
import org.modelix.model.lazy.RepositoryId
import org.modelix.model.persistent.HashUtil
import org.modelix.model.persistent.MapBaseStore
import org.modelix.model.server.api.ModelQuery
import org.modelix.model.server.api.v2.VersionDelta
import kotlin.jvm.Synchronized
import kotlin.time.Duration.Companion.seconds

class ModelClientV2(
    private val httpClient: HttpClient,
    val baseUrl: String,
) : IModelClientV2 {
    private var clientId: Int = 0
    private var idGenerator: IIdGenerator = IdGeneratorDummy()
    private var userId: String? = null
    private val kvStore = UncommitedEntriesStore()
    val store = ObjectStoreCache(kvStore) // TODO the store will accumulate garbage

    suspend fun init() {
        updateClientId()
        updateUserId()
    }

    private suspend fun updateClientId() {
        this.clientId = httpClient.post {
            url {
                takeFrom(baseUrl)
                appendPathSegments("generate-client-id")
            }
        }.bodyAsText().toInt()
        this.idGenerator = IdGenerator.getInstance(clientId)
    }

    suspend fun updateUserId() {
        userId = httpClient.get {
            url {
                takeFrom(baseUrl)
                appendPathSegments("user-id")
            }
        }.bodyAsText()
    }

    override fun getClientId(): Int = clientId

    override fun getIdGenerator(): IIdGenerator = idGenerator

    override fun getUserId(): String? = userId

    override suspend fun initRepository(repository: RepositoryId): IVersion {
        val response = httpClient.post {
            url {
                takeFrom(baseUrl)
                appendPathSegments("repositories", repository.id, "init")
            }
        }
        val delta = response.body<VersionDelta>()
        return createVersion(null, delta)
    }

    override suspend fun listRepositories(): List<RepositoryId> {
        return httpClient.get {
            url {
                takeFrom(baseUrl)
                appendPathSegments("repositories")
            }
        }.bodyAsText().lines().map { RepositoryId(it) }
    }

    override suspend fun listBranches(repository: RepositoryId): List<BranchReference> {
        return httpClient.get {
            url {
                takeFrom(baseUrl)
                appendPathSegments("repositories", repository.id, "branches")
            }
        }.bodyAsText().lines().map { repository.getBranchReference(it) }
    }

    override suspend fun loadVersion(versionHash: String, baseVersion: IVersion?): IVersion {
        val response = httpClient.post {
            url {
                takeFrom(baseUrl)
                appendPathSegments("versions", versionHash)
                if (baseVersion != null) {
                    parameters["lastKnown"] = (baseVersion as CLVersion).getShaHash()
                }
            }
        }
        val delta = Json.decodeFromString<VersionDelta>(response.bodyAsText())
        return createVersion(null, delta)
    }

    override suspend fun push(branch: BranchReference, version: IVersion): IVersion {
        require(version is CLVersion)
        version.write()
        val objects = kvStore.getUncommitedEntries().values.filterNotNull().toSet()
        val response = httpClient.post {
            url {
                takeFrom(baseUrl)
                appendPathSegments("repositories", branch.repositoryId.id, "branches", branch.branchName)
            }
            contentType(ContentType.Application.Json)
            val body = VersionDelta(version.hash, null, objects)
            setBody(body)
        }
        val mergedVersionDelta = response.body<VersionDelta>()
        return createVersion(version, mergedVersionDelta)
    }

    override suspend fun pull(branch: BranchReference, lastKnownVersion: IVersion?): IVersion {
        require(lastKnownVersion is CLVersion?)
        val response = httpClient.get {
            url {
                takeFrom(baseUrl)
                appendPathSegments("repositories", branch.repositoryId.id, "branches", branch.branchName)
                if (lastKnownVersion != null) {
                    parameters["lastKnown"] = lastKnownVersion.hash
                }
            }
        }
        return createVersion(lastKnownVersion, response.body())
    }

    override suspend fun poll(branch: BranchReference, lastKnownVersion: IVersion?): IVersion {
        require(lastKnownVersion is CLVersion?)
        val response = httpClient.get {
            url {
                takeFrom(baseUrl)
                appendPathSegments("repositories", branch.repositoryId.id, "branches", branch.branchName, "poll")
                if (lastKnownVersion != null) {
                    parameters["lastKnown"] = lastKnownVersion.hash
                }
            }
        }
        return createVersion(lastKnownVersion, response.body())
    }

    override suspend fun pull(branch: BranchReference, lastKnownVersion: IVersion?, filter: ModelQuery): IVersion {
        TODO("Not yet implemented")
    }

    override suspend fun poll(branch: BranchReference, lastKnownVersion: IVersion?, filter: ModelQuery): IVersion {
        TODO("Not yet implemented")
    }

    private fun createVersion(baseVersion: CLVersion?, delta: VersionDelta): CLVersion {
        return if (baseVersion == null) {
            CLVersion(
                delta.versionHash,
                store.also { it.keyValueStore.putAll(delta.objects.associateBy { HashUtil.sha256(it) }) }
            )
        } else if (delta.versionHash == baseVersion.hash) {
            baseVersion
        } else {
            require(baseVersion.store == store) { "baseVersion was not created by this client" }
            store.keyValueStore.putAll(delta.objects.associateBy { HashUtil.sha256(it) })
            CLVersion(
                delta.versionHash,
                baseVersion.store
            )
        }
    }

    companion object {
        fun builder(): ModelClientV2Builder = ModelClientV2PlatformSpecificBuilder()
    }
}

abstract class ModelClientV2Builder {
    protected var httpClient: HttpClient? = null
    protected var baseUrl: String = "https://localhost/model/v2"
    protected var authTokenProvider: (() -> String?)? = null

    fun build(): ModelClientV2 {
        return ModelClientV2(
            httpClient?.config { configureHttpClient(this) } ?: createHttpClient(),
            baseUrl
        )
    }

    fun url(url: String): ModelClientV2Builder {
        baseUrl = url
        return this
    }

    fun client(httpClient: HttpClient): ModelClientV2Builder {
        this.httpClient = httpClient
        return this
    }

    fun authToken(provider: () -> String?): ModelClientV2Builder {
        authTokenProvider = provider
        return this
    }

    protected open fun configureHttpClient(config: HttpClientConfig<*>) {
        config.apply {
            expectSuccess = true
            followRedirects = false
            install(ContentNegotiation) {
                json()
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 1.seconds.inWholeMilliseconds
                requestTimeoutMillis = 30.seconds.inWholeMilliseconds
            }
            install(HttpRequestRetry) {
                retryOnExceptionOrServerErrors(maxRetries = 3)
                exponentialDelay()
                modifyRequest {
                    try {
//                    connectionStatus = ConnectionStatus.SERVER_ERROR
                        this.response?.call?.client?.coroutineContext?.let { CoroutineScope(it) }?.launch {
                            response?.let { println(it.bodyAsText()) }
                        }
                    } catch (e: Exception) {
                        LOG.debug(e) { "" }
                    }
                }
            }
        }
    }

    protected abstract fun createHttpClient(): HttpClient

    companion object {
        private val LOG = mu.KotlinLogging.logger {}
    }
}

expect class ModelClientV2PlatformSpecificBuilder() : ModelClientV2Builder

private class UncommitedEntriesStore() : MapBaseStore() {
    private var uncommitedEntries: MutableMap<String, String?> = HashMap()

    @Synchronized
    fun getUncommitedEntries(): Map<String, String?> {
        val result = uncommitedEntries
        uncommitedEntries = HashMap()
        return result
    }

    @Synchronized
    override fun putAll(entries: Map<String, String?>) {
        uncommitedEntries.putAll(entries)
        super.putAll(entries)
    }
}
