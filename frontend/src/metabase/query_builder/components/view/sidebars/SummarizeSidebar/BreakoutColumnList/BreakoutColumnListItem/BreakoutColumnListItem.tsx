import cx from "classnames";
import type { MouseEvent } from "react";
import type React from "react";
import { useCallback } from "react";
import { t } from "ttag";

import {
  HoverParent,
  QueryColumnInfoIcon,
} from "metabase/common/components/MetadataInfo/QueryColumnInfoIcon";
import { useTranslateContent } from "metabase/content-translation/hooks";
import { ColumnBucketPickerPopover } from "metabase/querying/common/components/QueryColumnPicker/ColumnBucketPickerPopover";
import { Box, type BoxProps, Button, Flex, Icon, Tooltip } from "metabase/ui";
import * as Lib from "metabase-lib";

import BreakoutColumnListItemS from "./BreakoutColumnListItem.module.css";

interface BreakoutColumnListItemProps {
  query: Lib.Query;
  stageIndex: number;
  item: Lib.ColumnDisplayInfo & { column: Lib.ColumnMetadata };
  breakout?: Lib.BreakoutClause;
  isPinned?: boolean;
  onAddBreakout: (column: Lib.ColumnMetadata) => void;
  onUpdateBreakout: (
    breakout: Lib.BreakoutClause,
    column: Lib.ColumnMetadata,
  ) => void;
  onRemoveBreakout: (breakout: Lib.BreakoutClause) => void;
  onReplaceBreakouts?: (column: Lib.ColumnMetadata) => void;
}

const Root = ({
  children,
  isSelected,
  ...props
}: BoxProps & { isSelected?: boolean; children: React.ReactNode }) => {
  return (
    <Box
      component="li"
      {...props}
      className={cx(props.className, BreakoutColumnListItemS.Root, {
        [BreakoutColumnListItemS.isSelected]: isSelected,
        [BreakoutColumnListItemS.isNotSelected]: !isSelected,
      })}
    >
      {children}
    </Box>
  );
};

export function BreakoutColumnListItem({
  query,
  stageIndex,
  item,
  breakout,
  isPinned = false,
  onAddBreakout,
  onUpdateBreakout,
  onRemoveBreakout,
  onReplaceBreakouts,
}: BreakoutColumnListItemProps) {
  return null;
}
