import { createHash, randomBytes } from "node:crypto";

const ALPHABET =
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";

const ENTITY_ID_LENGTH = 21;

const ENTITY_ID_PATTERN = /^[A-Za-z0-9_-]{21}$/;

const toEntityId = (bytes: Buffer) =>
  Array.from(
    bytes.subarray(0, ENTITY_ID_LENGTH),
    (byte) => ALPHABET[byte & 63],
  ).join("");

export const isEntityId = (value: unknown): value is string =>
  typeof value === "string" && ENTITY_ID_PATTERN.test(value);

export const generateEntityId = () => toEntityId(randomBytes(ENTITY_ID_LENGTH));

/**
 * The entity ID the backend gives an app's copy of the `kind` entity
 * `sourceEntityId`, mirroring `derived-entity-id` in
 * `metabase-enterprise.data-apps.resource-files`, so the offline build check can
 * tell an action definition that now points at a different source action.
 */
export const getDerivedEntityId = (
  kind: "action",
  collectionEntityId: string,
  sourceEntityId: string,
) =>
  toEntityId(
    createHash("sha256")
      .update([kind, collectionEntityId, sourceEntityId].join("\u0000"))
      .digest(),
  );
