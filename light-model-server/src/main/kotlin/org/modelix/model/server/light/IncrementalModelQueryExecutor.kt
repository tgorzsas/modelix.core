/*
 * Copyright (c) 2022.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.modelix.model.server.light

import org.modelix.incremental.IncrementalIndex
import org.modelix.incremental.IncrementalList
import org.modelix.model.api.INode
import org.modelix.model.api.INodeReference
import org.modelix.model.api.INodeReferenceSerializer
import org.modelix.model.api.getAncestors
import org.modelix.model.api.getDescendants
import org.modelix.model.server.api.AndFilter
import org.modelix.model.server.api.ContainsOperator
import org.modelix.model.server.api.EndsWithOperator
import org.modelix.model.server.api.EqualsOperator
import org.modelix.model.server.api.Filter
import org.modelix.model.server.api.FilterByConceptId
import org.modelix.model.server.api.FilterByConceptLongName
import org.modelix.model.server.api.FilterByProperty
import org.modelix.model.server.api.IsNotNullOperator
import org.modelix.model.server.api.IsNullOperator
import org.modelix.model.server.api.MatchesRegexOperator
import org.modelix.model.server.api.ModelQuery
import org.modelix.model.server.api.OrFilter
import org.modelix.model.server.api.QueryAllChildren
import org.modelix.model.server.api.QueryAncestors
import org.modelix.model.server.api.QueryById
import org.modelix.model.server.api.QueryChildren
import org.modelix.model.server.api.QueryDescendants
import org.modelix.model.server.api.QueryParent
import org.modelix.model.server.api.QueryReference
import org.modelix.model.server.api.QueryRootNode
import org.modelix.model.server.api.RootOrSubquery
import org.modelix.model.server.api.RootQuery
import org.modelix.model.server.api.StartsWithOperator
import org.modelix.model.server.api.StringOperator
import org.modelix.model.server.api.Subquery

/**
 * Not thread safe.
 */
class IncrementalModelQueryExecutor(val rootNode: INode) {
    private var lastUpdateSession: UpdateSession? = null
    private var currentUpdateSession: UpdateSession? = null

    private val nodeEntriesIndex: IncrementalIndex<INodeReference, NodeCacheEntry> = IncrementalIndex()

    fun invalidate(changedNodes: Set<INodeReference>) {
        lastUpdateSession?.invalidate(changedNodes)
    }

    /**
     * Returns the nodes that changed since the last execution
     */
    fun update(query: ModelQuery, visitor: (INode) -> Unit): Set<INode> {
        if (currentUpdateSession != null) throw IllegalStateException("Already executing a query")
        val updateSession = UpdateSession(lastUpdateSession?.cacheEntry?.takeIf { it.modelQuery == query } ?: ModelQueryCacheEntry(query, rootNode))
        try {
            currentUpdateSession = updateSession

            currentUpdateSession!!.cacheEntry.validate(visitor)
            nodeEntriesIndex.update(currentUpdateSession!!.cacheEntry.listOfAllNodeEntries)

            lastUpdateSession = currentUpdateSession
        } finally {
            currentUpdateSession = null
        }
        return updateSession.changedNodes
    }

    private inner class UpdateSession(val cacheEntry: ModelQueryCacheEntry) {
        val changedNodes: Set<INode> = HashSet()

        fun invalidate(invalidatedNodes: Set<INodeReference>) {
            invalidatedNodes.asSequence().flatMap { nodeEntriesIndex.lookup(it) }.forEach { it.invalidate() }
        }
    }
}

private sealed class CacheEntry() {
    abstract val parent: CacheEntry?
    private var valid = false
    private var anyDescendantInvalid = true

    var listOfAllNodeEntries: IncrementalList<Pair<INodeReference, NodeCacheEntry>> = IncrementalList.empty()

    protected abstract fun doValidate(validationVisitor: IValidationVisitor)
    protected abstract fun getChildren(): Sequence<CacheEntry>

    fun isValid() = valid
    fun isAnyTransitiveInvalid() = anyDescendantInvalid

    fun validate(validationVisitor: IValidationVisitor) {
        if (valid) {
            if (anyDescendantInvalid) {
                validateDescendants(validationVisitor)
            }
        } else {
            doValidate(validationVisitor)
            validateDescendants(validationVisitor)
            valid = true
        }
    }

    fun validateDescendants(validationVisitor: IValidationVisitor) {
        getChildren().forEach { it.validate(validationVisitor) }
        updateListOfAllNodeEntries()
        anyDescendantInvalid = false
    }

    fun invalidate() {
        valid = false
        parent?.descendantInvalidated()
    }

    fun descendantInvalidated() {
        if (anyDescendantInvalid) return
        anyDescendantInvalid = true
        parent?.descendantInvalidated()
    }

    protected open fun updateListOfAllNodeEntries() {
        listOfAllNodeEntries = IncrementalList.concat(getChildren().map { it.listOfAllNodeEntries }.toList())
    }
}

private class NodeCacheEntry(override val parent: CacheEntry, val node: INode, val producedByQuery: RootOrSubquery) : CacheEntry() {
    var filterResult = true
    private var children: List<SubqueryCacheEntry> = emptyList()

    override fun doValidate(validationVisitor: IValidationVisitor) {
        filterResult = applyFilters()
        if (filterResult) validationVisitor(node)
        children = if (filterResult) {
            producedByQuery.queries.map { SubqueryCacheEntry(this, it) }
        } else {
            emptyList()
        }
    }

    override fun getChildren(): Sequence<CacheEntry> {
        return children.asSequence()
    }

    protected override fun updateListOfAllNodeEntries() {
        listOfAllNodeEntries = IncrementalList.concat((sequenceOf(IncrementalList.of(node.reference to this)) + getChildren().map { it.listOfAllNodeEntries }).toList())
    }

    private fun applyFilters(): Boolean {
        return when (producedByQuery) {
            is RootQuery -> true
            is Subquery -> producedByQuery.filters.all { applyFilter(it) }
        }
    }

    private fun applyFilter(filter: Filter): Boolean {
        // When adding new types of filters the invalidation algorithm might be adjusted if the filter has
        // dependencies outside the node.
        return when (filter) {
            is AndFilter -> filter.filters.all { applyFilter(it) }
            is OrFilter -> filter.filters.isEmpty() || filter.filters.any { applyFilter(it) }
            is FilterByConceptId -> node.getConceptReference()?.getUID() == filter.conceptUID
            is FilterByProperty -> applyStringOperator(node.getPropertyValue(filter.role), filter.operator)
            is FilterByConceptLongName -> applyStringOperator(node.concept?.getLongName(), filter.operator)
        }
    }

    private fun applyStringOperator(value: String?, operator: StringOperator): Boolean {
        return when (operator) {
            is ContainsOperator -> value?.contains(operator.substring) ?: false
            is EndsWithOperator -> value?.endsWith(operator.suffix) ?: false
            is EqualsOperator -> value == operator.value
            is IsNotNullOperator -> value != null
            is IsNullOperator -> value == null
            is MatchesRegexOperator -> value?.matches(Regex(operator.pattern)) ?: false
            is StartsWithOperator -> value?.startsWith(operator.prefix) ?: false
        }
    }
}

private sealed class QueryCacheEntry(parent: CacheEntry) : CacheEntry() {
    abstract val query: RootOrSubquery
    protected var children: Map<INode, NodeCacheEntry> = emptyMap()

    protected abstract fun queryNodes(): Sequence<INode>

    override fun doValidate(validationVisitor: IValidationVisitor) {
        children = queryNodes().associateWith { children[it] ?: NodeCacheEntry(this, it, query) }
    }

    override fun getChildren(): Sequence<CacheEntry> {
        return children.values.asSequence()
    }
}

private class SubqueryCacheEntry(override val parent: NodeCacheEntry, override val query: Subquery) : QueryCacheEntry(parent) {
    override fun queryNodes(): Sequence<INode> {
        return when (query) {
            is QueryAllChildren -> parent.node.allChildren.asSequence()
            is QueryAncestors -> parent.node.getAncestors(false)
            is QueryChildren -> parent.node.getChildren(query.role).asSequence()
            is QueryDescendants -> parent.node.getDescendants(false)
            is QueryParent -> listOfNotNull(parent.node.parent).asSequence()
            is QueryReference -> listOfNotNull(parent.node.getReferenceTarget(query.role)).asSequence()
        }
    }
}

private class RootQueryCacheEntry(override val parent: ModelQueryCacheEntry, override val query: RootQuery) : QueryCacheEntry(parent) {
    override fun queryNodes(): Sequence<INode> {
        return when (query) {
            is QueryById -> {
                val resolved = INodeReferenceSerializer.deserialize(query.nodeId).resolveNode(parent.rootNode.getArea())
                if (resolved != null) {
                    sequenceOf(resolved)
                } else {
                    emptySequence()
                }
            }

            is QueryRootNode -> {
                sequenceOf(parent.rootNode)
            }
        }
    }
}

private class ModelQueryCacheEntry(val modelQuery: ModelQuery, val rootNode: INode) : CacheEntry() {
    private var children: List<RootQueryCacheEntry> = modelQuery.queries.map { RootQueryCacheEntry(this, it) }

    override val parent: CacheEntry?
        get() = null

    override fun doValidate(validationVisitor: IValidationVisitor) {
    }

    override fun getChildren(): Sequence<CacheEntry> {
        return children.asSequence()
    }
}

private typealias IValidationVisitor = (INode) -> Unit