import type { PropsWithChildren } from "react";

import { Box, Group, Stack, Text } from "metabase/ui";

import {
  TableActionFormInputType,
  type TableActionFormParameter,
} from "../../api/types";

import S from "./TableActionFormModal.module.css";

type ActionFormModalParameterProps = PropsWithChildren<{
  parameter: TableActionFormParameter;
}>;

const INPUT_TYPE_TO_DISPLAY_TYPE_MAP: Record<TableActionFormInputType, string> =
  {
    [TableActionFormInputType.Text]: "text",
    [TableActionFormInputType.Textarea]: "description",
    [TableActionFormInputType.Date]: "date",
    [TableActionFormInputType.DateTime]: "date",
    [TableActionFormInputType.Dropdown]: "category",
    [TableActionFormInputType.Boolean]: "boolean",
    [TableActionFormInputType.Integer]: "integer",
    [TableActionFormInputType.Float]: "float",
  };

export function TableActionFormModalParameter({
  parameter,
  children,
}: ActionFormModalParameterProps) {
  return null;
}
