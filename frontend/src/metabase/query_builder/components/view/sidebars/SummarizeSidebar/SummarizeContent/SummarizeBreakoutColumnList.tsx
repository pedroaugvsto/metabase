import { t } from "ttag";

import { Space, Stack, type StackProps, Title } from "metabase/ui";

import type { UpdateQueryHookProps } from "../../../../../hooks/types";
import { BreakoutColumnList } from "../BreakoutColumnList";

type SummarizeBreakoutColumnListProps = UpdateQueryHookProps & StackProps;

export const SummarizeBreakoutColumnList = ({
  query,
  onQueryChange,
  stageIndex,
  ...containerProps
}: SummarizeBreakoutColumnListProps) => null;
