import type {GeneratedLanguage} from "./GeneratedLanguage";
import type {INodeJS} from "./INodeJS";
import {TypedNode} from "./TypedNode";

export class LanguageRegistry {
  public static INSTANCE: LanguageRegistry = new LanguageRegistry();
  private languages: Map<String, GeneratedLanguage> = new Map();
  private nodeWrappers: Map<String, (node: INodeJS)=>TypedNode> | undefined = undefined

  public register(lang: GeneratedLanguage): void {
    this.languages.set(lang.name, lang);
    this.nodeWrappers = undefined
  }

  public unregister(lang: GeneratedLanguage): void {
    this.languages.delete(lang.name);
  }

  public isRegistered(lang: GeneratedLanguage): boolean {
    return this.languages.has(lang.name)
  }

  public getAll(): GeneratedLanguage[] {
    return Array.from(this.languages.values());
  }

  public wrapNode(node: INodeJS): TypedNode {
    if (this.nodeWrappers === undefined) {
      this.nodeWrappers = new Map()
      for (let lang of this.languages.values()) {
        for (let entry of lang.nodeWrappers.entries()) {
          this.nodeWrappers.set(entry[0], entry[1])
        }
      }
    }
    let conceptUID = node.getConceptReference()?.getUID();
    if (conceptUID !== undefined) {
      let wrapper = this.nodeWrappers.get(conceptUID)
      if (wrapper !== undefined) {
        return wrapper(node)
      }
    }
    return new TypedNode(node)
  }
}
