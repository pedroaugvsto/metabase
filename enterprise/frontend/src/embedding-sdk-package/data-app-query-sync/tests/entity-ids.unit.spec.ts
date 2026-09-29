import {
  generateEntityId,
  getDerivedEntityId,
  isEntityId,
} from "../entity-ids";

describe("entity IDs", () => {
  it("generates NanoIDs", () => {
    const first = generateEntityId();

    expect(isEntityId(first)).toBe(true);
    expect(generateEntityId()).not.toBe(first);
  });

  it("derives the same copy entity ID as the backend", () => {
    // `derived-entity-id` in `metabase-enterprise.data-apps.resource-files` returns this for the same input.
    expect(
      getDerivedEntityId(
        "action",
        "shop-cccccccccccccccc",
        "sourceActionEntity01",
      ),
    ).toBe("JIon8kCXvwU9wR4WtI5Aa");
  });
});
