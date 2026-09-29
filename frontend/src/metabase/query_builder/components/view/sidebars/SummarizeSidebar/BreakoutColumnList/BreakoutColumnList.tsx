import { type ChangeEvent, useCallback, useMemo, useState } from "react";
import { t } from "ttag";

import { useDebouncedValue } from "metabase/common/hooks/use-debounced-value";
import { useTranslateContent } from "metabase/content-translation/hooks";
import { Box, DelayGroup, Icon, Input, TextInput } from "metabase/ui";
import { SEARCH_DEBOUNCE_DURATION } from "metabase/utils/constants";
import { isNotNull } from "metabase/utils/types";
import * as Lib from "metabase-lib";

import {
  type UpdateQueryHookProps,
  useBreakoutQueryHandlers,
} from "../../../../../hooks";

import BreakoutColumnListS from "./BreakoutColumnList.module.css";
import { BreakoutColumnListItem } from "./BreakoutColumnListItem";
import { getBreakoutListItem, getColumnSections, isPinnedColumn } from "./util";

export type BreakoutColumnListProps = UpdateQueryHookProps;

export function BreakoutColumnList({
  query,
  onQueryChange,
  stageIndex = -1,
}: BreakoutColumnListProps) {
  return null;
}
