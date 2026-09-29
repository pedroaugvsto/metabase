import { isEntityId } from "./entity-ids";
import { isPositiveInteger, isRecord } from "./guards";
import type { RenderResponse, RenderedResourceFile } from "./types";

const RESOURCE_MODELS = new Set(["Collection", "Card", "Action"]);

const isRenderedResourceFile = (
  value: unknown,
): value is RenderedResourceFile =>
  isRecord(value) &&
  typeof value.path === "string" &&
  typeof value.content === "string" &&
  typeof value.model === "string" &&
  RESOURCE_MODELS.has(value.model) &&
  isEntityId(value.entity_id);

const isRenderResponse = (value: unknown): value is RenderResponse =>
  isRecord(value) &&
  Array.isArray(value.files) &&
  value.files.every(isRenderedResourceFile) &&
  Array.isArray(value.actions) &&
  value.actions.every(
    (action) =>
      isRecord(action) &&
      isPositiveInteger(action.id) &&
      isEntityId(action.entity_id),
  );

export interface RenderRequest {
  collection: { entity_id: string; name: string };
  queries: Array<{
    entity_id: string;
    name: string;
    query: { stages: Array<Record<string, unknown>> };
  }>;
  actions: Array<{ id: number }>;
}

export class MetabaseClient {
  constructor(
    private readonly baseUrl: string,
    private readonly apiKey: string,
  ) {}

  /** Renders the app's resource files. Metabase writes nothing. */
  async renderResources(request: RenderRequest): Promise<RenderResponse> {
    const url = new URL(this.baseUrl);
    url.pathname = `${url.pathname.replace(/\/?$/, "/")}api/apps/resources/render`;

    const response = await fetch(url, {
      method: "POST",
      signal: AbortSignal.timeout(60_000),
      headers: {
        "content-type": "application/json",
        "x-api-key": this.apiKey,
      },
      body: JSON.stringify(request),
    });

    if (!response.ok) {
      throw new Error(
        // eslint-disable-next-line metabase/no-literal-metabase-strings -- CLI protocol error names the remote service.
        `Metabase returned ${response.status} for POST ${url}: ${await response.text()}`,
      );
    }

    const body: unknown = await response.json();

    if (!isRenderResponse(body)) {
      throw new Error(
        // eslint-disable-next-line metabase/no-literal-metabase-strings -- CLI protocol error names the remote service.
        "Metabase returned resources this version of the CLI does not understand.",
      );
    }

    return body;
  }
}
