import { canonicalJson, getQueryFingerprint } from "../canonical";

import { setupResourceSyncTests } from "./setup";

describe("query canonicalization", () => {
  setupResourceSyncTests();

  it("fingerprints the export name and the authored query, independent of property order", () => {
    const fingerprint = getQueryFingerprint("Orders", {
      source: { type: "table", id: 1 },
      limit: 5,
    });

    expect(fingerprint).toMatch(/^v1:sha256:[a-f0-9]{64}$/);
    expect(
      getQueryFingerprint("Orders", {
        limit: 5,
        source: { id: 1, type: "table", name: "Orders", fields: {} },
        savedQuestionEntityId: "questionEntityId00099",
      }),
    ).toBe(fingerprint);
    expect(
      getQueryFingerprint("Orders", {
        source: { type: "table", id: 2 },
        limit: 5,
      }),
    ).not.toBe(fingerprint);
    expect(
      getQueryFingerprint("RecentOrders", {
        source: { type: "table", id: 1 },
        limit: 5,
      }),
    ).not.toBe(fingerprint);
    expect(canonicalJson({ b: 1, a: 2 })).toBe('{"a":2,"b":1}');
    expect(canonicalJson({ ä: 2, z: 1 })).toBe('{"z":1,"ä":2}');
  });

  it("rejects non-serializable query definitions", () => {
    expect(() => canonicalJson({ value: undefined })).toThrow(
      "cannot contain undefined values",
    );
  });

  it("rejects unsupported values and invalid table sources", () => {
    const circular: { self?: unknown } = {};
    circular.self = circular;

    expect(() => canonicalJson(circular)).toThrow("circular references");
    expect(() => canonicalJson({ value: new Date() })).toThrow(
      "only plain objects",
    );
    expect(() => canonicalJson({ value: Infinity })).toThrow(
      "non-finite numbers",
    );
    expect(() => getQueryFingerprint("Orders", {})).toThrow(
      "valid table source",
    );
    expect(() =>
      getQueryFingerprint("Orders", { source: { type: "table", id: 0 } }),
    ).toThrow("valid table source");
  });
});
