import * as Lib from "metabase-lib";

import type { ListItem, ListSection } from "./types";

export function getBreakoutListItem(
  query: Lib.Query,
  stageIndex: number,
  breakout: Lib.BreakoutClause,
): ListItem | undefined {
  const column = Lib.breakoutColumn(query, stageIndex, breakout);
  if (column == null) {
    return;
  }

  const columnWithoutBucketing = Lib.withBinning(
    Lib.withTemporalBucket(column, null),
    null,
  );
  const columnInfo = Lib.displayInfo(query, stageIndex, columnWithoutBucketing);
  return { ...columnInfo, column, breakout };
}

function getColumnListItems(
  query: Lib.Query,
  stageIndex: number,
  breakouts: Lib.BreakoutClause[],
  column: Lib.ColumnMetadata,
): ListItem[] {
  const columnInfo = Lib.displayInfo(query, stageIndex, column);
  const { breakoutPositions = [] } = columnInfo;
  if (breakoutPositions.length === 0) {
    return [{ ...columnInfo, column }];
  }

  return breakoutPositions.reduce((items: ListItem[], index) => {
    const breakout = breakouts[index];
    const column = Lib.breakoutColumn(query, stageIndex, breakout);
    if (column != null) {
      items.push({
        ...columnInfo,
        column,
        breakout,
      });
    }
    return items;
  }, []);
}

export function getColumnSections(
  query: Lib.Query,
  stageIndex: number,
  columns: Lib.ColumnMetadata[],
  searchQuery: string,
): ListSection[] {
  return [];
}

export function isPinnedColumn(
  query: Lib.Query,
  stageIndex: number,
  column: Lib.ColumnMetadata,
  pinnedItemCount: number,
): boolean {
  const { breakoutPositions = [] } = Lib.displayInfo(query, stageIndex, column);
  return (
    breakoutPositions.length > 0 &&
    breakoutPositions.every((breakoutIndex) => breakoutIndex < pinnedItemCount)
  );
}
