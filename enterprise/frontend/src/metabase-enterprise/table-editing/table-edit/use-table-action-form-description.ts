import { useCallback, useEffect } from "react";

import { useDescribeActionFormMutation } from "../api";
import type { TableActionId, TableEditingActionScope } from "../api/types";

type UseTableDescribeTmpModalProps = {
  actionId: TableActionId;
  scope: TableEditingActionScope;
  fetchOnMount?: boolean;
};

export const useActionFormDescription = ({
  actionId,
  scope,
  fetchOnMount = true,
}: UseTableDescribeTmpModalProps) => {
  const [fetchModalDescription, { data, isLoading }] =
    useDescribeActionFormMutation();

  const refetch = useCallback(() => {
    return;
  }, [actionId, scope, fetchModalDescription]);

  useEffect(() => {
    if (fetchOnMount) {
      refetch();
    }
  }, [fetchOnMount, refetch]);

  return {
    refetch,
    data,
    isLoading,
  };
};
