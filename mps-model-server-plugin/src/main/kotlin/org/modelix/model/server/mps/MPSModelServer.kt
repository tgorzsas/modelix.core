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
package org.modelix.model.server.mps

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import jetbrains.mps.ide.project.ProjectHelper
import jetbrains.mps.project.MPSProject
import org.modelix.model.api.INode
import org.modelix.model.api.runSynchronized
import org.modelix.model.mpsadapters.MPSRepositoryAsNode
import org.modelix.model.server.light.LightModelServer
import java.util.Collections

@Service(Service.Level.PROJECT)
class MPSModelServerForProject(private val project: Project) : Disposable {

    init {
        service<MPSModelServer>().registerProject(project)
    }

    override fun dispose() {
        service<MPSModelServer>().unregisterProject(project)
    }
}

@Service(Service.Level.APP)
class MPSModelServer : Disposable {

    private var server: LightModelServer? = null
    private val projects: MutableSet<Project> = Collections.synchronizedSet(HashSet())

    fun registerProject(project: Project) {
        projects.add(project)
        ensureStarted()
    }

    fun unregisterProject(project: Project) {
        projects.remove(project)
    }

    private fun getMPSProjects(): List<MPSProject> {
        return runSynchronized(projects) {
            projects.mapNotNull { it.getComponent(MPSProject::class.java) }
        }
    }

    private fun getRootNode(): INode? {
        return getMPSProjects().asSequence().map {
            MPSRepositoryAsNode(it.repository)
        }.firstOrNull()
    }

    fun ensureStarted() {
        runSynchronized(this) {
            if (server != null) return

            println("starting modelix server")

            server = LightModelServer.builder()
                .port(48305)
                .rootNode(::getRootNode)
                .healthCheck(object : LightModelServer.IHealthCheck {
                    override val id: String
                        get() = "indexer"
                    override val enabledByDefault: Boolean
                        get() = false

                    override fun run(output: java.lang.StringBuilder): Boolean {
                        var allSmart = true
                        for (project in getMPSProjects()) {
                            project.repository.modelAccess.runReadAction {
                                val indexerDone =
                                    !DumbService.getInstance(ProjectHelper.toIdeaProject(project)).isDumb
                                if (!indexerDone) {
                                    output.append("  indexer running on project ").append(project.toString())
                                    allSmart = false
                                }
                            }
                        }
                        return allSmart
                    }
                })
                .healthCheck(object : LightModelServer.IHealthCheck {
                    override val id: String
                        get() = "projects"
                    override val enabledByDefault: Boolean
                        get() = false

                    override fun run(output: StringBuilder): Boolean {
                        val projects = getMPSProjects()
                        output.append("${projects.size} projects found")
                        projects.forEach { output.append("  $it") }
                        return projects.isNotEmpty()
                    }
                })
                .healthCheck(object : LightModelServer.IHealthCheck {
                    override val id: String
                        get() = "virtualFolders"
                    override val enabledByDefault: Boolean
                        get() = false

                    override fun run(output: StringBuilder): Boolean {
                        val projects = getMPSProjects()
                        for (project in projects) {
                            val modules = project.projectModules
                            val virtualFolders = modules
                                .mapNotNull { project.getPath(it)?.virtualFolder }
                                .filter { it.isNotEmpty() }
                            output.append("project $project contains ${modules.size} modules with ${virtualFolders.size} virtual folders")
                            if (virtualFolders.isNotEmpty()) return true
                        }
                        return false
                    }
                })
                .build()
            server!!.start()
        }
    }

    fun ensureStopped() {
        runSynchronized(this) {
            if (server == null) return
            println("stopping modelix server")
            server?.stop()
            server = null
        }
    }

    override fun dispose() {
        ensureStopped()
    }
}

class MPSModelServerStartupActivity : StartupActivity.Background {
    override fun runActivity(project: Project) {
        project.service<MPSModelServerForProject>() // just ensure it's initialized
    }
}
