import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import {
  ArrowDown,
  ArrowUp,
  GripVertical,
  Pencil,
  Plus,
  Copy,
  Save,
  Trash2,
  X,
} from 'lucide-react-native';
import { useEffect, useRef, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { Alert, Modal, Pressable, RefreshControl, StyleSheet, View } from 'react-native';
import DraggableFlatList, { RenderItemParams } from 'react-native-draggable-flatlist';

import type { BudgetTemplate, TemplateEntry } from '@/api/contracts';
import { displayError } from '@/api/display-error';
import { useYuukaApi } from '@/api/use-yuuka-api';
import type { TemplateEntryPayload } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { IconButton } from '@/components/icon-button';
import { Screen } from '@/components/screen';
import {
  EmptyState,
  ErrorState,
  StaleBanner,
  YuukaLoadingState,
  YuukaRefreshIndicator,
} from '@/components/states';
import { TextField } from '@/components/text-field';
import { formatMoney } from '@/domain/money';
import { TemplateFormValues, templateFormSchema } from '@/features/templates/form-schemas';
import { TemplateEntryEditor } from '@/features/templates/template-entry-editor';
import { useMinimumVisibleDuration } from '@/hooks/use-minimum-visible-duration';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

const typeLabels = {
  BILL: 'Bill',
  SPENDING_BUCKET: 'Spending Bucket',
  SINKING_FUND: 'Planned Savings',
} as const;

export default function EditTemplateScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const api = useYuukaApi();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const query = useQuery({ queryKey: ['template', id], queryFn: () => api.template(id) });
  const showColdLoader = useMinimumVisibleDuration(query.isPending && !query.data, 1000);
  const [editingEntry, setEditingEntry] = useState<TemplateEntry | null>(null);
  const [entryEditorVisible, setEntryEditorVisible] = useState(false);
  const [templateEditorVisible, setTemplateEditorVisible] = useState(false);
  const archiveInFlight = useRef(false);
  const duplicateInFlight = useRef(false);
  const restoreInFlight = useRef(false);
  const [archiveLocked, setArchiveLocked] = useState(false);
  const [duplicateLocked, setDuplicateLocked] = useState(false);
  const [restoreLocked, setRestoreLocked] = useState(false);

  const invalidate = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['template', id] }),
      queryClient.invalidateQueries({ queryKey: ['templates'] }),
    ]);
  };
  const templateMutation = useMutation({
    mutationFn: (values: { description: string | null; name: string; version: number }) =>
      api.updateTemplate(id, values),
    onSuccess: invalidate,
  });
  const archiveMutation = useMutation({
    mutationFn: () => {
      if (!query.data) throw new Error('Refresh the template first.');
      return api.archiveTemplate(id, query.data.version);
    },
    onSuccess: invalidate,
    onSettled: () => {
      archiveInFlight.current = false;
      setArchiveLocked(false);
    },
  });
  const restoreMutation = useMutation({
    mutationFn: () => {
      if (!query.data) throw new Error('Refresh the template first.');
      return api.restoreTemplate(id, query.data.version);
    },
    onSuccess: invalidate,
    onSettled: () => {
      restoreInFlight.current = false;
      setRestoreLocked(false);
    },
  });
  const duplicateMutation = useMutation({
    mutationFn: () => api.duplicateTemplate(id),
    onSuccess: async (template) => {
      await queryClient.invalidateQueries({ queryKey: ['templates'] });
      router.replace(`/templates/${template.id}`);
    },
    onSettled: () => {
      duplicateInFlight.current = false;
      setDuplicateLocked(false);
    },
  });
  const entryMutation = useMutation({
    mutationFn: async (action: {
      entry?: TemplateEntry | null;
      payload?: TemplateEntryPayload;
      type: 'add' | 'delete' | 'update';
    }) => {
      if (action.type === 'delete' && action.entry) {
        return api.deleteTemplateEntry(action.entry.id, action.entry.version);
      }
      if (action.type === 'update' && action.entry && action.payload) {
        return api.updateTemplateEntry(action.entry.id, {
          ...action.payload,
          version: action.entry.version,
        });
      }
      if (action.payload) return api.addTemplateEntry(id, action.payload);
      throw new Error('Template entry action is incomplete.');
    },
    onSuccess: invalidate,
  });
  const reorderMutation = useMutation({
    mutationFn: (entryIds: string[]) => {
      if (!query.data) throw new Error('Refresh the template before reordering.');
      return api.reorderTemplateEntries(id, entryIds, query.data.version);
    },
    onSuccess: invalidate,
  });

  if (showColdLoader) {
    return (
      <Screen contentContainerStyle={styles.center}>
        <YuukaLoadingState message="Loading template..." />
      </Screen>
    );
  }
  if (query.isError && !query.data) {
    return (
      <Screen contentContainerStyle={styles.center}>
        <ErrorState
          message={displayError(query.error, settings.currencyCode, 'The template could not load.')}
          retry={() => query.refetch()}
        />
      </Screen>
    );
  }
  const template = query.data;
  if (!template) return null;
  const entries = [...template.entries].sort((left, right) => left.position - right.position);

  function moveEntry(index: number, offset: number) {
    const ids = entries.map((entry) => entry.id);
    const target = index + offset;
    if (target < 0 || target >= ids.length || reorderMutation.isPending) return;
    [ids[index], ids[target]] = [ids[target], ids[index]];
    reorderMutation.mutate(ids);
  }

  function archiveTemplate() {
    if (archiveInFlight.current || archiveLocked || archiveMutation.isPending) return;
    archiveInFlight.current = true;
    setArchiveLocked(true);
    archiveMutation.mutate();
  }

  function restoreTemplate() {
    if (restoreInFlight.current || restoreLocked || restoreMutation.isPending) return;
    restoreInFlight.current = true;
    setRestoreLocked(true);
    restoreMutation.mutate();
  }

  function duplicateTemplate() {
    if (duplicateInFlight.current || duplicateLocked || duplicateMutation.isPending) return;
    duplicateInFlight.current = true;
    setDuplicateLocked(true);
    duplicateMutation.mutate();
  }

  function confirmArchiveTemplate() {
    Alert.alert(
      'Archive template?',
      'Archive this template so it cannot be used for new paychecks until restored. Existing paychecks created from it stay unchanged.',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Archive',
          style: 'destructive',
          onPress: archiveTemplate,
        },
      ],
    );
  }

  return (
    <>
      <Stack.Screen
        options={{
          headerShown: true,
          title: template.name,
          headerStyle: { backgroundColor: colors.surface },
          headerTintColor: colors.text,
        }}
      />
      <Screen>
        <DraggableFlatList
          contentContainerStyle={styles.list}
          data={entries}
          keyExtractor={(entry) => entry.id}
          ListEmptyComponent={
            <EmptyState
              action={
                template.archived ? undefined : (
                  <Button
                    icon={Plus}
                    label="Add entry"
                    onPress={() => {
                      setEditingEntry(null);
                      setEntryEditorVisible(true);
                    }}
                  />
                )
              }
              mascot="clipboard"
              message="Add bills, spending buckets, or planned savings to this template."
              title="No template entries"
            />
          }
          ListHeaderComponent={
            <DetailHeader
              archiveError={archiveMutation.error}
              archiving={archiveLocked || archiveMutation.isPending}
              duplicateError={duplicateMutation.error}
              duplicating={duplicateLocked || duplicateMutation.isPending}
              onAdd={() => {
                setEditingEntry(null);
                setEntryEditorVisible(true);
              }}
              onArchive={confirmArchiveTemplate}
              onDuplicate={duplicateTemplate}
              onEdit={() => setTemplateEditorVisible(true)}
              onRestore={restoreTemplate}
              refreshing={query.isFetching && Boolean(query.data)}
              reorderError={reorderMutation.error}
              restoreError={restoreMutation.error}
              restoring={restoreLocked || restoreMutation.isPending}
              stale={query.isError}
              template={template}
            />
          }
          onDragEnd={({ data }) => {
            if (!template.archived && !reorderMutation.isPending) {
              reorderMutation.mutate(data.map((entry) => entry.id));
            }
          }}
          refreshControl={
            <RefreshControl
              accessibilityLabel="Refresh template"
              colors={['transparent']}
              onRefresh={() => void query.refetch()}
              progressBackgroundColor="transparent"
              refreshing={query.isRefetching}
              testID="template-refresh-control"
              tintColor="transparent"
            />
          }
          renderItem={(params: RenderItemParams<TemplateEntry>) => {
            const index = entries.findIndex((entry) => entry.id === params.item.id);
            return (
              <TemplateEntryRow
                drag={params.drag}
                entry={params.item}
                editable={!template.archived}
                isFirst={index === 0}
                isLast={index === entries.length - 1}
                position={index + 1}
                onEdit={() => {
                  setEditingEntry(params.item);
                  setEntryEditorVisible(true);
                }}
                onMoveDown={() => moveEntry(index, 1)}
                onMoveUp={() => moveEntry(index, -1)}
                reorderDisabled={template.archived || reorderMutation.isPending}
              />
            );
          }}
        />
      </Screen>
      <TemplateDetailsEditor
        onClose={() => setTemplateEditorVisible(false)}
        onSubmit={(values) => templateMutation.mutateAsync(values).then(() => undefined)}
        template={template}
        visible={templateEditorVisible && !template.archived}
      />
      {!template.archived ? (
        <TemplateEntryEditor
          entry={editingEntry}
          onClose={() => setEntryEditorVisible(false)}
          onDelete={
            editingEntry
              ? () =>
                  entryMutation
                    .mutateAsync({ type: 'delete', entry: editingEntry })
                    .then(() => undefined)
              : undefined
          }
          onSubmit={(payload) =>
            entryMutation
              .mutateAsync({ type: editingEntry ? 'update' : 'add', entry: editingEntry, payload })
              .then(() => undefined)
          }
          visible={entryEditorVisible}
        />
      ) : null}
    </>
  );
}

function DetailHeader({
  archiveError,
  archiving,
  duplicateError,
  duplicating,
  onAdd,
  onArchive,
  onDuplicate,
  onEdit,
  onRestore,
  refreshing,
  reorderError,
  restoreError,
  restoring,
  stale,
  template,
}: {
  archiveError: Error | null;
  archiving: boolean;
  duplicateError: Error | null;
  duplicating: boolean;
  onAdd: () => void;
  onArchive: () => void;
  onDuplicate: () => void;
  onEdit: () => void;
  onRestore: () => void;
  refreshing: boolean;
  reorderError: Error | null;
  restoreError: Error | null;
  restoring: boolean;
  stale: boolean;
  template: BudgetTemplate;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  return (
    <View style={styles.header}>
      <YuukaRefreshIndicator visible={refreshing} />
      {stale ? <StaleBanner /> : null}
      <View style={styles.titleRow}>
        <View style={styles.titleBlock}>
          <AppText variant="title">{template.name}</AppText>
          <AppText style={{ color: colors.muted }} variant="caption">
            {template.entryCount} {template.entryCount === 1 ? 'entry' : 'entries'} | Updated{' '}
            {formatDate(template.updatedAt)}
            {template.archived ? ' | Archived' : ''}
          </AppText>
        </View>
        <AppText variant="money">
          {formatMoney(template.defaultTotalMinor, settings.currencyCode)}
        </AppText>
      </View>
      {template.description ? (
        <AppText style={{ color: colors.muted }} variant="caption">
          {template.description}
        </AppText>
      ) : null}
      <View style={styles.actions}>
        {template.archived ? null : (
          <>
            <Button icon={Pencil} label="Edit template" onPress={onEdit} variant="secondary" />
            <Button icon={Plus} label="Add entry" onPress={onAdd} />
          </>
        )}
        <Button
          icon={Copy}
          label="Duplicate template"
          loading={duplicating}
          onPress={onDuplicate}
          variant="secondary"
        />
        {template.archived ? (
          <Button
            label="Restore template"
            loading={restoring}
            onPress={onRestore}
            variant="primary"
          />
        ) : (
          <Button
            icon={Trash2}
            label="Archive template"
            loading={archiving}
            onPress={onArchive}
            variant="danger"
          />
        )}
      </View>
      {reorderError ? (
        <AppText style={{ color: colors.danger }} variant="error">
          {displayError(reorderError, settings.currencyCode, 'Template order was not saved.')}
        </AppText>
      ) : null}
      {archiveError ? (
        <AppText style={{ color: colors.danger }} variant="error">
          {displayError(archiveError, settings.currencyCode, 'The template was not archived.')}
        </AppText>
      ) : null}
      {restoreError ? (
        <AppText style={{ color: colors.danger }} variant="error">
          {displayError(restoreError, settings.currencyCode, 'The template was not restored.')}
        </AppText>
      ) : null}
      {duplicateError ? (
        <AppText style={{ color: colors.danger }} variant="error">
          {displayError(duplicateError, settings.currencyCode, 'The template was not duplicated.')}
        </AppText>
      ) : null}
    </View>
  );
}

function TemplateEntryRow({
  drag,
  editable,
  entry,
  isFirst,
  isLast,
  onEdit,
  onMoveDown,
  onMoveUp,
  position,
  reorderDisabled,
}: {
  drag?: () => void;
  editable: boolean;
  entry: TemplateEntry;
  isFirst: boolean;
  isLast: boolean;
  onEdit: () => void;
  onMoveDown: () => void;
  onMoveUp: () => void;
  position: number;
  reorderDisabled: boolean;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const amount = formatMoney(entry.defaultAmountMinor, settings.currencyCode);
  return (
    <View style={[styles.entryRow, { borderBottomColor: colors.border }]}>
      <View
        accessible
        accessibilityLabel={`Template entry ${position}: ${entry.name}, ${amount}, ${typeLabels[entry.entryType]}`}
        style={styles.entryMain}
      >
        <View style={styles.titleRow}>
          <View style={styles.titleBlock}>
            <AppText numberOfLines={2} variant="label">
              {entry.name}
            </AppText>
            <AppText style={{ color: colors.muted }} variant="caption">
              {typeLabels[entry.entryType]}
              {entry.entryType === 'BILL' && entry.paymentMethod
                ? ` | ${entry.paymentMethod === 'MANUAL' ? 'Manual Pay' : 'Autopay'}`
                : ''}
              {entry.defaultDueOffsetDays == null
                ? ''
                : ` | Due ${entry.defaultDueOffsetDays} days from income`}
            </AppText>
          </View>
          <AppText variant="money">{amount}</AppText>
        </View>
      </View>
      <View style={styles.rowActions}>
        {editable ? (
          <>
            <IconButton
              disabled={isFirst || reorderDisabled}
              icon={ArrowUp}
              label={`Move ${entry.name} up`}
              onPress={onMoveUp}
            />
            <IconButton
              disabled={isLast || reorderDisabled}
              icon={ArrowDown}
              label={`Move ${entry.name} down`}
              onPress={onMoveDown}
            />
            <IconButton
              disabled={reorderDisabled}
              icon={GripVertical}
              label={`Drag ${entry.name}`}
              onLongPress={drag}
              onPress={drag ?? (() => undefined)}
            />
            <IconButton icon={Pencil} label={`Edit ${entry.name}`} onPress={onEdit} />
          </>
        ) : null}
      </View>
    </View>
  );
}

function TemplateDetailsEditor({
  onClose,
  onSubmit,
  template,
  visible,
}: {
  onClose: () => void;
  onSubmit: (values: {
    description: string | null;
    name: string;
    version: number;
  }) => Promise<void>;
  template: BudgetTemplate;
  visible: boolean;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const {
    control,
    formState: { errors, isSubmitting },
    handleSubmit,
    reset,
    setError,
  } = useForm<TemplateFormValues>({
    resolver: zodResolver(templateFormSchema),
    defaultValues: defaults(template),
  });

  useEffect(() => {
    if (visible) reset(defaults(template));
  }, [reset, template, visible]);

  async function submit(values: TemplateFormValues) {
    try {
      await onSubmit({
        name: values.name.trim(),
        description: values.description.trim() || null,
        version: template.version,
      });
      onClose();
    } catch (error) {
      setError('root', {
        message: displayError(error, settings.currencyCode, 'The template was not saved.'),
      });
    }
  }

  return (
    <Modal animationType="slide" onRequestClose={onClose} visible={visible}>
      <View style={[styles.modalScreen, { backgroundColor: colors.background }]}>
        <View style={[styles.modalHeader, { borderBottomColor: colors.border }]}>
          <AppText variant="title">Edit template</AppText>
          <Pressable
            accessibilityLabel="Close template editor"
            onPress={onClose}
            style={styles.close}
          >
            <X color={colors.text} size={23} />
          </Pressable>
        </View>
        <View style={styles.modalForm}>
          <Field control={control} error={errors.name?.message} label="Name" name="name" />
          <Field control={control} label="Description (optional)" multiline name="description" />
          {errors.root?.message ? (
            <AppText style={{ color: colors.danger }} variant="error">
              {errors.root.message}
            </AppText>
          ) : null}
          <Button
            icon={Save}
            label="Save template"
            loading={isSubmitting}
            onPress={handleSubmit(submit)}
          />
        </View>
      </View>
    </Modal>
  );
}

function Field({
  control,
  error,
  label,
  multiline,
  name,
}: {
  control: ReturnType<typeof useForm<TemplateFormValues>>['control'];
  error?: string;
  label: string;
  multiline?: boolean;
  name: keyof TemplateFormValues;
}) {
  return (
    <Controller
      control={control}
      name={name}
      render={({ field }) => (
        <TextField
          error={error}
          label={label}
          multiline={multiline}
          onBlur={field.onBlur}
          onChangeText={field.onChange}
          value={String(field.value ?? '')}
        />
      )}
    />
  );
}

function defaults(template: BudgetTemplate): TemplateFormValues {
  return { name: template.name, description: template.description ?? '' };
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' }).format(
    new Date(value),
  );
}

const styles = StyleSheet.create({
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  center: { alignItems: 'center', justifyContent: 'center' },
  close: { alignItems: 'center', height: 44, justifyContent: 'center', width: 44 },
  entryMain: { flex: 1, gap: 11 },
  entryRow: {
    alignItems: 'center',
    borderBottomWidth: 1,
    borderRadius: 8,
    flexDirection: 'row',
    gap: 12,
    paddingHorizontal: 8,
    paddingVertical: 15,
  },
  header: { gap: 16, paddingBottom: 6 },
  list: { flexGrow: 1, padding: 16 },
  modalForm: { gap: 16, padding: 18 },
  modalHeader: {
    alignItems: 'center',
    borderBottomWidth: 1,
    flexDirection: 'row',
    justifyContent: 'space-between',
    padding: 18,
  },
  modalScreen: { flex: 1 },
  rowActions: { alignItems: 'center', flexDirection: 'row', gap: 6, justifyContent: 'flex-end' },
  titleBlock: { flex: 1, gap: 3 },
  titleRow: {
    alignItems: 'flex-start',
    flexDirection: 'row',
    gap: 10,
    justifyContent: 'space-between',
  },
});
