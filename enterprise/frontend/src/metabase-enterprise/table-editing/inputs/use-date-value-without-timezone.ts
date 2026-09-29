import { useCallback, useMemo } from "react";

import { dayjs } from "metabase/dayjs";

export enum DateValueTimezoneFormat {
  Always,
  Never,
  NonZero,
}

type UseDateValueWithoutTimezoneOptions = {
  valueFormat?: string;
  timezoneFormat?: DateValueTimezoneFormat;
};

export const useDateValueWithoutTimezone = (
  value: string | null,
  {
    valueFormat = "YYYY-MM-DDTHH:mm:ss",
    timezoneFormat = DateValueTimezoneFormat.NonZero,
  }: UseDateValueWithoutTimezoneOptions = {},
) => {
  return {};
};
