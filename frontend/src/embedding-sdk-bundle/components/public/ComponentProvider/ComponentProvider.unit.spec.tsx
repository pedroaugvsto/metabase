jest.mock("embedding-sdk-bundle/analytics/tracker", () => ({
  useInitSdkTracker: jest.fn(),
}));

// Heavy hooks not under test — prevent real network/redux side-effects.
jest.mock("embedding-sdk-bundle/hooks/private/use-init-data", () => ({
  useInitDataInternal: jest.fn(),
}));

import { settlePendingUpdates } from "__support__/ui";
import { useInitSdkTracker } from "embedding-sdk-bundle/analytics/tracker";
import { renderWithSDKProviders } from "embedding-sdk-bundle/test/__support__/ui";

const mockUseInitSdkTracker = jest.mocked(useInitSdkTracker);

describe("ComponentProvider — tracker wiring", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("passes locale != null as the third argument when locale is set", async () => {
    renderWithSDKProviders(<div />, {
      componentProviderProps: {
        authConfig: { metabaseInstanceUrl: "https://metabase.example.com" },
        locale: "en",
      },
    });

    expect(mockUseInitSdkTracker).toHaveBeenCalledWith(
      expect.anything(),
      expect.anything(),
      true,
    );

    // This test asks for a locale, so its catalogue load has to finish here.
    await settlePendingUpdates();
  });

  it("passes false as the third argument when locale is not set", () => {
    renderWithSDKProviders(<div />, {
      componentProviderProps: {
        authConfig: { metabaseInstanceUrl: "https://metabase.example.com" },
        locale: undefined,
      },
    });

    expect(mockUseInitSdkTracker).toHaveBeenCalledWith(
      expect.anything(),
      expect.anything(),
      false,
    );
  });
});
