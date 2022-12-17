package org.modelix.editor

import org.modelix.metamodel.GeneratedChildListLink
import org.modelix.metamodel.GeneratedConcept
import org.modelix.metamodel.GeneratedSingleChildLink
import org.modelix.metamodel.ITypedConcept
import org.modelix.metamodel.ITypedNode
import org.modelix.metamodel.typed
import org.modelix.metamodel.untypedReference
import org.modelix.model.api.serialize

class ConceptEditor<NodeT : ITypedNode, ConceptT : ITypedConcept>(
    val declaredConcept: GeneratedConcept<NodeT, ConceptT>,
    val templateBuilder: (subConcept: GeneratedConcept<NodeT, ConceptT>)->CellTemplate<NodeT, ConceptT>
) {
    fun apply(subConcept: GeneratedConcept<NodeT, ConceptT>): CellTemplate<NodeT, ConceptT> {
        return templateBuilder(subConcept)
    }

    fun apply(editor: EditorEngine, node: NodeT): CellData {
        return apply(node._concept._concept as GeneratedConcept<NodeT, ConceptT>).apply(editor, node)
    }
}

fun <NodeT : ITypedNode, ConceptT : ITypedConcept> createDefaultConceptEditor(concept: GeneratedConcept<NodeT, ConceptT>): ConceptEditor<NodeT, ConceptT> {
    return ConceptEditor(concept) { subConcept ->
        CellTemplateBuilder(CollectionCellTemplate(subConcept)).apply {
            subConcept.getShortName().cell()
            curlyBrackets {
                for (property in subConcept.getAllProperties()) {
                    newLine()
                    label { property.name.cell() }
                    property.cell()
                }
                for (link in subConcept.getAllReferenceLinks()) {
                    newLine()
                    label { link.name.cell() }
                    link.typed()?.cell(presentation = { untypedReference().serialize() })
                }
                for (link in subConcept.getAllChildLinks()) {
                    newLine()
                    label { link.name.cell() }
                    when (val l = link.typed()) {
                        is GeneratedSingleChildLink -> l.cell()
                        is GeneratedChildListLink -> {
                            newLine()
                            indented {
                                l.vertical()
                            }
                        }
                    }
                }
            }
        }.template
    }
}

private fun <ConceptT : ITypedConcept, NodeT : ITypedNode> CellTemplateBuilder<NodeT, ConceptT>.label(
    body: CellTemplateBuilder<NodeT, ConceptT>.()->Unit
) {
    horizontal {
        textColor("LightGray")
        body()
        noSpace()
        ":".cell()
    }
}
