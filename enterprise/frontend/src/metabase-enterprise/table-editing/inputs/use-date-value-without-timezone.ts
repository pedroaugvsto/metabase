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
  const { timezoneOffsetString, timezoneOffset, localDate } = useMemo(() => {
    return {};
  }, [value, valueFormat]);

  const restoreTimezone = useCallback(
    (value: string | null) => {
      if (!value) {
        return null;
      }

      if (
        timezoneFormat === DateValueTimezoneFormat.Never ||
        (timezoneFormat === DateValueTimezoneFormat.NonZero &&
          timezoneOffset === 0)
      ) {
        return dayjs(value).format(valueFormat);
      }

      return dayjs(value).format(valueFormat) + timezoneOffsetString;
    },
    [valueFormat, timezoneOffsetString, timezoneOffset, timezoneFormat],
  );

  return { localDate, timezoneOffsetString, restoreTimezone };
};
