import cx from "classnames";
import { useFormik } from "formik";
import { useCallback, useEffect } from "react";
import { t } from "ttag";

import { LeaveConfirmModal } from "metabase/common/components/LeaveConfirmModal";
import Animation from "metabase/css/core/animation.module.css";
import { Box, Button, Center, Flex, Loader, Modal } from "metabase/ui";
import type { RowValue } from "metabase-types/api";

import type {
  DescribeActionFormResponse,
  RowCellsWithPkValue,
  TableActionFormParameter,
} from "../../api/types";

import { ModalParameterActionInput } from "./ModalParameterActionInput";
import S from "./TableActionFormModal.module.css";
import { TableActionFormModalParameter } from "./TableActionFormModalParameter";
import { useActionFormUnsavedLeaveConfirmation } from "./use-action-form-unsaved-leave-confirmation";

interface CreateRowActionFormModalProps {
  opened: boolean;
  description?: DescribeActionFormResponse;
  isLoading: boolean;
  initialValues?: RowCellsWithPkValue;
  onClose: () => void;
  onSubmit: (data: RowCellsWithPkValue) => Promise<boolean>;
}

export function CreateRowActionFormModal({
  opened,
  description,
  isLoading,
  initialValues,
  onClose,
  onSubmit,
}: CreateRowActionFormModalProps) {
  return null;
}

type ModalFormInputProps = {
  initialValue?: RowValue;
  parameter: TableActionFormParameter;
  onChange: (field: string, value: RowValue) => void;
};

export function ModalFormInput({
  initialValue,
  parameter,
  onChange,
}: ModalFormInputProps) {
  const handleChange = useCallback(
    (value: RowValue) => {
      onChange(parameter.id, value);
    },
    [parameter.id, onChange],
  );

  return (
    <ModalParameterActionInput
      initialValue={initialValue?.toString()}
      parameter={parameter}
      onChange={handleChange}
    />
  );
}
