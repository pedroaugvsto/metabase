import { isFK } from "metabase-lib/v1/types/utils/isa";

import type { TableActionFormParameter } from "../api/types";
import { TableActionFormInputType } from "../api/types";

import {
  TableActionInputBoolean,
  type TableActionInputBooleanProps,
} from "./TableActionInputBoolean";
import {
  TableActionInputDate,
  type TableActionInputDateProps,
} from "./TableActionInputDate";
import {
  TableActionInputDateTime,
  type TableActionInputDateTimeProps,
} from "./TableActionInputDateTime";
import {
  TableActionInputNumber,
  type TableActionInputNumberProps,
} from "./TableActionInputNumber";
import {
  TableActionInputSearchableSelect,
  type TableActionInputSearchableSelectProps,
} from "./TableActionInputSearchableSelect";
import {
  TableActionInputText,
  type TableActionInputTextProps,
} from "./TableActionInputText";
import {
  TableActionInputTextarea,
  type TableActionInputTextareaProps,
} from "./TableActionInputTextarea";

type TableActionInputProps =
  | TableActionInputDateProps
  | TableActionInputDateTimeProps
  | TableActionInputTextProps
  | TableActionInputTextareaProps
  | TableActionInputSearchableSelectProps
  | TableActionInputBooleanProps
  | TableActionInputNumberProps;

export type ParameterActionInputProps = TableActionInputProps & {
  parameter: TableActionFormParameter;
};

export function ParameterActionInput(props: ParameterActionInputProps) {
  return null;
}
