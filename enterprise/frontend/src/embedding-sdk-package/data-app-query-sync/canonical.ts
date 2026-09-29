import { createHash } from "node:crypto";

import { isRecord } from "./guards";

/** Serializes a query with stable object-key ordering. */
export const canonicalJson = (value: unknown): string =>
  JSON.stringify(canonicalize(value));

/**
 * A stable hash of what a saved question is rendered from: the export's name,
 * which names the question, and the authored query. The source is reduced to
 * its table, so regenerating the schema's metadata doesn't read as a change.
 */
export function getQueryFingerprint(
  exportName: string,
  query: Record<string, unknown>,
) {
  const source = query.source;

  if (
    !isRecord(source) ||
    source.type !== "table" ||
    typeof source.id !== "number" ||
    !Number.isInteger(source.id) ||
    source.id <= 0
  ) {
    throw new Error("Synchronized queries must use a valid table source.");
  }

  const authoredDsl: Record<string, unknown> = {
    ...query,
    source: { type: "table", id: source.id },
  };

  delete authoredDsl.savedQuestionEntityId;

  const hash = createHash("sha256")
    .update(canonicalJson({ name: exportName, query: authoredDsl }))
    .digest("hex");

  return `v1:sha256:${hash}`;
}

/** Validates a query value and recursively sorts object keys. */
function canonicalize(value: unknown, seen = new Set<object>()): unknown {
  const type = typeof value;

  if (value === null || type === "string" || type === "boolean") {
    return value;
  }

  if (type === "number") {
    if (!Number.isFinite(value)) {
      throw new Error("Query definitions cannot contain non-finite numbers.");
    }

    return value;
  }

  if (typeof value !== "object") {
    throw new Error(`Query definitions cannot contain ${type} values.`);
  }

  if (seen.has(value)) {
    throw new Error("Query definitions cannot contain circular references.");
  }

  if (!Array.isArray(value)) {
    const prototype = Object.getPrototypeOf(value);

    if (prototype !== Object.prototype && prototype !== null) {
      throw new Error("Query definitions must contain only plain objects.");
    }
  }

  seen.add(value);

  const result = Array.isArray(value)
    ? value.map((item) => canonicalize(item, seen))
    : Object.fromEntries(
        Object.entries(value)
          .sort(([first], [second]) => {
            if (first < second) {
              return -1;
            }
            if (first > second) {
              return 1;
            }
            return 0;
          })
          .map(([key, item]) => [key, canonicalize(item, seen)]),
      );

  seen.delete(value);

  return result;
}
