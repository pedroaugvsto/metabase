import { EnterpriseApi } from "metabase-enterprise/api/api";

import {
  type DescribeActionFormRequest,
  type DescribeActionFormResponse,
  TableActionId,
  type TableDeleteRowsRequest,
  type TableDeleteRowsResponse,
  type TableInsertRowsRequest,
  type TableInsertRowsResponse,
  type TableUpdateRowsRequest,
  type TableUpdateRowsResponse,
} from "./types";

export const tableEditingApi = EnterpriseApi.injectEndpoints({
  endpoints: (builder) => ({}),
});

export const {
  useInsertTableRowsMutation,
  useUpdateTableRowsMutation,
  useDeleteTableRowsMutation,
  useDescribeActionFormMutation,
} = tableEditingApi;
