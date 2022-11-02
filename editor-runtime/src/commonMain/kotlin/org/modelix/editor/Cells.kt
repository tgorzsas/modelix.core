package org.modelix.editor

import org.modelix.metamodel.ITypedNode

interface IFreezable {
    fun freeze()
    fun checkNotFrozen()
}

open class Freezable : IFreezable {
    private var frozen: Boolean = false
    override fun freeze() {
        frozen = true
    }

    fun isFrozen() = frozen

    override fun checkNotFrozen() {
        if (frozen) {
            throw IllegalStateException("Cell cannot be modified anymore")
        }
    }
}

interface ICellHolder {
    fun getCell(): Cell
    fun tryGetCell(): Cell?
}

class CellHolder(private val cell: Cell) : ICellHolder {
    override fun getCell(): Cell {
        return cell
    }

    override fun tryGetCell(): Cell {
        return cell
    }
}

interface ILocalOrChildNodeCell {

}

open class CellData : Freezable(), ILocalOrChildNodeCell {
    val children: MutableList<ILocalOrChildNodeCell> = ArrayList()
    val actions: MutableList<ICellAction> = ArrayList()
    val properties = CellProperties()

    fun addChild(child: ILocalOrChildNodeCell) {
        children.add(child)
    }

    open fun layout(buffer: TextLayouter, cell: Cell) {
        val body: ()->Unit = {
            if (properties[CommonCellProperties.onNewLine]) buffer.onNewLine()
            if (properties[CommonCellProperties.noSpace]) buffer.noSpace()
            cell.getChildren().forEach { it.layout(buffer) }
            if (properties[CommonCellProperties.noSpace]) buffer.noSpace()
        }
        if (properties[CommonCellProperties.indentChildren]) {
            buffer.withIndent(body)
        } else {
            body()
        }
    }
}

class ChildNodeCellReference(val childNode: ITypedNode) : ILocalOrChildNodeCell {

}

class Cell(val data: CellData = CellData()) : Freezable() {
    var parent: Cell? = null
    private val children: MutableList<Cell> = ArrayList()

    override fun freeze() {
        if (isFrozen()) return
        super.freeze()
        data.freeze()
        children.forEach { it.freeze() }
    }

    override fun toString(): String {
        return children.toString()
    }

    fun addChild(child: Cell) {
        require(child.parent == null) { "$child already has a parent ${child.parent}" }
        children.add(child)
        child.parent = this
    }

    fun removeChild(child: Cell) {
        require(child.parent == this) { "$child is not a child of $this" }
        children.remove(child)
        child.parent = null
    }

    fun getChildren(): List<Cell> = children

    fun layout(buffer: TextLayouter) {
        data.layout(buffer, this)
    }

    fun <T> getProperty(key: CellPropertyKey<T>): T {
        return if (data.properties.isSet(key)) {
            data.properties.get(key)
        } else {
            parent.let { if (it != null) it.getProperty(key) else key.defaultValue }
        }
    }
}

class CellProperties : Freezable() {
    private val properties: MutableMap<CellPropertyKey<*>, Any?> = HashMap()
    operator fun <T> get(key: CellPropertyKey<T>): T {
        return if (properties.containsKey(key)) properties[key] as T else key.defaultValue
    }

    fun isSet(key: CellPropertyKey<*>): Boolean = properties.containsKey(key)

    operator fun <T> set(key: CellPropertyKey<T>, value: T) {
        checkNotFrozen()
        properties[key] = value
    }

    fun copy(): CellProperties {
        return CellProperties().also { it.addAll(this) }
    }

    fun addAll(from: CellProperties) {
        checkNotFrozen()
        properties += from.properties
    }
}

class CellPropertyKey<E>(val name: String, val defaultValue: E)

enum class ECellLayout {
    VERTICAL,
    HORIZONTAL;
}

object CommonCellProperties {
    val layout = CellPropertyKey<ECellLayout>("layout", ECellLayout.HORIZONTAL)
    val indentChildren = CellPropertyKey<Boolean>("indent-children", false)
    val onNewLine = CellPropertyKey<Boolean>("on-new-line", false)
    val noSpace = CellPropertyKey<Boolean>("no-space", false)
    val textColor = CellPropertyKey<String?>("text-color", null)
    val backgroundColor = CellPropertyKey<String?>("background-color", null)
}

interface ICellAction {

}

class TextCellData(val text: String, val placeholderText: String) : CellData() {
    fun getVisibleText(cell: Cell): String {
        return if (cell.getChildren().isEmpty()) {
            text.ifEmpty { placeholderText }
        } else {
            """$text<${cell.getChildren()}>"""
        }
    }

    override fun layout(buffer: TextLayouter, cell: Cell) {
        if (properties[CommonCellProperties.onNewLine]) buffer.onNewLine()
        if (properties[CommonCellProperties.noSpace]) buffer.noSpace()
        buffer.append(LayoutableCell(cell))
        if (properties[CommonCellProperties.noSpace]) buffer.noSpace()
    }
}
