package org.modelix.model.server.handlers

import io.ktor.http.encodeURLPathPart
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.html.respondHtmlTemplate
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.html.FlowContent
import kotlinx.html.FlowOrInteractiveOrPhrasingContent
import kotlinx.html.a
import kotlinx.html.h1
import kotlinx.html.i
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import org.modelix.model.server.templates.PageWithMenuBar

class RepositoryOverview(private val repoManager: RepositoriesManager) {

    fun init(application: Application) {
        application.routing {
            get("/repos/") {
                call.respondHtmlTemplate(PageWithMenuBar("repos/", "..")) {
                    headContent { title("Repositories") }
                    bodyContent { buildMainPage() }
                }
            }
        }
    }

    private fun FlowContent.buildMainPage() {
        h1 { +"Choose Repository" }
        val repositories = repoManager.getRepositories()
        if (repositories.isEmpty()) {
            p { i { +"No repositories available, add one" } }
        } else {
            table {
                thead {
                    tr {
                        th { +"Repository" }
                        th { +"Branch" }
                        th {
                            colSpan = "2"
                            +"Actions"
                        }
                    }
                }
                tbody {
                    for (repository in repositories) {
                        val branches = repoManager.getBranches(repository)
                        tr {
                            td {
                                rowSpan = branches.size.coerceAtLeast(1).plus(1).toString()
                                +repository.id
                            }
                        }
                        if (branches.isEmpty()) {
                            tr {
                                td { }
                                td { }
                                td { }
                            }
                        } else {
                            for (branch in branches) {
                                tr {
                                    td {
                                        span {
                                            +branch.branchName
                                        }
                                    }
                                    td {
                                        buildHistoryLink(branch.repositoryId.id, branch.branchName)
                                    }
                                    td {
                                        val latestVersion = repoManager.getVersion(branch)
                                            ?: throw RuntimeException("Branch not found: $branch")
                                        a("../content/${latestVersion.getContentHash()}/") {
                                            +"Explore Latest Version"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun FlowOrInteractiveOrPhrasingContent.buildHistoryLink(repositoryId: String, branchName: String) {
    a("../history/${repositoryId.encodeURLPathPart()}/${branchName.encodeURLPathPart()}/") {
        +"Show History"
    }
}
