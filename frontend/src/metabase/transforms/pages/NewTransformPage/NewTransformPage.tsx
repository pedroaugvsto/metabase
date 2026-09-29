import { useDisclosure } from "@mantine/hooks";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { t } from "ttag";

import { skipToken, useGetCardQuery } from "metabase/api";
import { NotFound } from "metabase/common/components/ErrorPages";
import { LeaveRouteConfirmModal } from "metabase/common/components/LeaveConfirmModal";
import { Link } from "metabase/common/components/Link";
import { LoadingAndErrorWrapper } from "metabase/common/components/LoadingAndErrorWrapper";
import { DataStudioBreadcrumbs } from "metabase/common/data-studio/components/DataStudioBreadcrumbs";
import { PageContainer } from "metabase/common/data-studio/components/PageContainer";
import {
  PaneHeader,
  PaneHeaderActions,
  PaneHeaderInput,
} from "metabase/common/data-studio/components/PaneHeader";
import { useMetadataProvider } from "metabase/metadata-store";
import { loadQueryEditorWithParameters } from "metabase/parameters/components/QueryEditorWithParameters";
import { PLUGIN_TRANSFORMS_PYTHON } from "metabase/plugins";
import { getInitialUiState } from "metabase/querying/editor/components/QueryEditor";
import { type Location, useNavigate, useParams } from "metabase/router";
import { useTransformPermissions } from "metabase/transforms/hooks/use-transform-permissions";
import { Box, Center } from "metabase/ui";
import * as Urls from "metabase/urls";
import * as Lib from "metabase-lib";
import type {
  Database,
  DraftTransformSource,
  Transform,
} from "metabase-types/api";

import { TransformEditor } from "../../components/TransformEditor";
import { NAME_MAX_LENGTH } from "../../constants";
import { useSourceState } from "../../hooks/use-source-state";
import { getValidationResult, isCompleteSource } from "../../utils";

import { CreateTransformModal } from "./CreateTransformModal";
import {
  getInitialCardSource,
  getInitialNativeSource,
  getInitialPythonSource,
  getInitialQuerySource,
} from "./utils";

type NewTransformPageProps = {
  initialSource: DraftTransformSource;
};

function NewTransformPage({ initialSource }: NewTransformPageProps) {
  const {
    transformsDatabases,
    remoteSyncReadOnly,
    isLoadingDatabases,
    databasesError: error,
  } = useTransformPermissions();

  const isEditorLoaded = useQueryEditorChunk();
  const isLoading = isLoadingDatabases || !isEditorLoaded;

  if (isLoading || error != null || transformsDatabases == null) {
    return (
      <Center h="100%">
        <LoadingAndErrorWrapper loading={isLoading} error={error} />
      </Center>
    );
  }

  if (remoteSyncReadOnly) {
    return (
      <PageContainer pos="relative" data-testid="transform-query-editor">
        <PaneHeader
          breadcrumbs={
            <DataStudioBreadcrumbs>
              <Link key="transform-list" to={Urls.transformList()}>
                {t`Data transformation`}
              </Link>
            </DataStudioBreadcrumbs>
          }
        />
        <NotFound />
      </PageContainer>
    );
  }

  return (
    <NewTransformPageBody
      initialSource={initialSource}
      databases={transformsDatabases}
    />
  );
}

type NewTransformPageBodyProps = {
  initialSource: DraftTransformSource;
  databases: Database[];
};

function NewTransformPageBody({
  initialSource,
  databases,
}: NewTransformPageBodyProps) {
  return null;
}

export function NewQueryTransformPage() {
  const initialSource = useMemo(() => getInitialQuerySource(), []);
  return <NewTransformPage initialSource={initialSource} />;
}

export function NewNativeTransformPage() {
  const initialSource = useMemo(() => getInitialNativeSource(), []);
  return <NewTransformPage initialSource={initialSource} />;
}

export function NewPythonTransformPage() {
  const initialSource = useMemo(() => getInitialPythonSource(), []);
  return <NewTransformPage initialSource={initialSource} />;
}

type NewCardTransformPageParams = {
  cardId: string;
};

export function NewCardTransformPage() {
  const params = useParams<NewCardTransformPageParams>();
  const cardId = Urls.extractEntityId(params.cardId);
  const {
    data: card,
    isLoading,
    error,
  } = useGetCardQuery(cardId != null ? { id: cardId } : skipToken);

  const initialSource = useMemo(
    () => (card != null ? getInitialCardSource(card) : undefined),
    [card],
  );

  if (isLoading || error || initialSource == null) {
    return (
      <Center h="100%">
        <LoadingAndErrorWrapper loading={isLoading} error={error} />
      </Center>
    );
  }

  return <NewTransformPage initialSource={initialSource} />;
}

// The editor is a separate chunk. Folding it into the wait this page already
// does for its own data means one wait rather than two in a row.
const useQueryEditorChunk = () => {
  const [isLoaded, setIsLoaded] = useState(false);
  useEffect(() => {
    let cancelled = false;
    loadQueryEditorWithParameters().then(() => {
      if (!cancelled) {
        setIsLoaded(true);
      }
    });
    return () => {
      cancelled = true;
    };
  }, []);
  return isLoaded;
};
