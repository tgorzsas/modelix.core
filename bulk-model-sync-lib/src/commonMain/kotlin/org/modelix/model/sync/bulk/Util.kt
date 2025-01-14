/*
 * Copyright (c) 2023.
 *
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

package org.modelix.model.sync.bulk

import mu.KLogger
import org.modelix.model.api.BuiltinLanguages
import org.modelix.model.data.ModelData
import org.modelix.model.data.NodeData

fun mergeModelData(models: Collection<ModelData>): ModelData {
    return ModelData(root = NodeData(children = models.map { it.root }))
}

@Deprecated("use collection parameter for better performance")
fun mergeModelData(vararg models: ModelData): ModelData {
    return ModelData(root = NodeData(children = models.map { it.root }))
}

internal fun logImportSize(nodeData: NodeData, logger: KLogger) {
    logger.debug { measureImportSize(nodeData).toString() }
}

private data class ImportSizeMetrics(
    var numModules: Int = 0,
    var numModels: Int = 0,
    val concepts: MutableSet<String> = mutableSetOf(),
    var numProperties: Int = 0,
    var numReferences: Int = 0,
) {
    override fun toString(): String {
        return """
            [Bulk Model Sync Import Size]
            number of modules: $numModules
            number of models: $numModels
            number of concepts: ${concepts.size}
            number of properties: $numProperties
            number of references: $numReferences
        """.trimIndent()
    }
}

private fun measureImportSize(data: NodeData, metrics: ImportSizeMetrics = ImportSizeMetrics()): ImportSizeMetrics {
    data.concept?.let { metrics.concepts.add(it) }

    when (data.concept) {
        BuiltinLanguages.MPSRepositoryConcepts.Module.getUID() -> metrics.numModules++
        BuiltinLanguages.MPSRepositoryConcepts.Model.getUID() -> metrics.numModels++
    }

    metrics.numProperties += data.properties.size
    metrics.numReferences += data.references.size

    data.children.forEach { measureImportSize(it, metrics) }
    return metrics
}
