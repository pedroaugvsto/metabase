export interface DiscoveredQuery {
  exportName: string;
  filePath: string;
  query: Record<string, unknown>;
  savedQuestionEntityId?: string;
  hash: string;
}

export interface DiscoveredAction {
  exportName: string;
  filePath: string;
  copiedActionEntityId?: string;
  sourceActionId: number;
  sourceActionEntityId?: string;
}

export type ResourceModel = "Collection" | "Card" | "Action";

/** A resource file as `POST /api/apps/resources/render` returns it. */
export interface RenderedResourceFile {
  /** Relative to the app's `resources/` directory. */
  path: string;
  content: string;
  model: ResourceModel;
  entity_id: string;
}

export interface RenderResponse {
  files: RenderedResourceFile[];
  /** Maps each source action to the entity ID of its copy. */
  actions: Array<{ id: number; entity_id: string }>;
}
