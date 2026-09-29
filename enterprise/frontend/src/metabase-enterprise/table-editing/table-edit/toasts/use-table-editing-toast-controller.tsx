import { useMemo } from "react";

import { useDispatch } from "metabase/redux";
import { addUndo } from "metabase/redux/undo";

import { ErrorUpdateToast } from "./ErrorUpdateToast";

export type TableEditingToastControler = {
  showSuccessToast: (message: string) => void;
};

export function useTableEditingToastController() {
  const dispatch = useDispatch();

  return useMemo(
    () => ({}),
    [dispatch],
  );
}
