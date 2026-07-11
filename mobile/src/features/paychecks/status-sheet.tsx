import { Check, X } from 'lucide-react-native';
import { useState } from 'react';
import { Modal, Pressable, StyleSheet, View } from 'react-native';

import { Entry, EntryStatus } from '@/api/contracts';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { SegmentedControl } from '@/components/segmented-control';
import { TextField } from '@/components/text-field';
import { useAppTheme } from '@/theme/use-app-theme';

const options = [
  { label: 'Not Paid', value: 'NOT_PAID' },
  { label: 'Processing', value: 'PROCESSING' },
  { label: 'Posted', value: 'POSTED' },
] as const;

export function StatusSheet({
  entry,
  onClose,
  onSubmit,
  visible,
}: {
  entry: Entry | null;
  onClose: () => void;
  onSubmit: (values: { effectiveAt: string; note: string; toStatus: EntryStatus }) => Promise<void>;
  visible: boolean;
}) {
  if (!visible || !entry) {
    return null;
  }

  return <StatusSheetContent entry={entry} onClose={onClose} onSubmit={onSubmit} />;
}

function StatusSheetContent({
  entry,
  onClose,
  onSubmit,
}: {
  entry: Entry;
  onClose: () => void;
  onSubmit: (values: { effectiveAt: string; note: string; toStatus: EntryStatus }) => Promise<void>;
}) {
  const { colors } = useAppTheme();
  const [toStatus, setToStatus] = useState<EntryStatus>(entry.status);
  const [effectiveAt, setEffectiveAt] = useState(new Date().toISOString());
  const [note, setNote] = useState('');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  async function submit() {
    if (Number.isNaN(Date.parse(effectiveAt))) {
      setError('Enter a valid ISO date and time.');
      return;
    }
    setSaving(true);
    setError('');
    try {
      await onSubmit({ effectiveAt: new Date(effectiveAt).toISOString(), note, toStatus });
      onClose();
    } catch (submissionError) {
      setError(
        submissionError instanceof Error ? submissionError.message : 'The status was not saved.',
      );
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal animationType="slide" onRequestClose={onClose} transparent visible>
      <Pressable
        accessibilityLabel="Close status editor"
        onPress={onClose}
        style={[styles.backdrop, { backgroundColor: colors.overlay }]}
      >
        <Pressable
          accessibilityViewIsModal
          onPress={(event) => event.stopPropagation()}
          style={[styles.sheet, { backgroundColor: colors.surface, borderColor: colors.border }]}
        >
          <View style={styles.handle} />
          <View style={styles.header}>
            <View style={styles.headerText}>
              <AppText variant="title">Update status</AppText>
              <AppText numberOfLines={1} style={{ color: colors.muted }} variant="caption">
                {entry.name}
              </AppText>
            </View>
            <Button icon={X} label="Cancel" onPress={onClose} variant="ghost" />
          </View>
          <SegmentedControl
            label="Entry status"
            onChange={setToStatus}
            options={options}
            value={toStatus}
          />
          <TextField
            autoCapitalize="none"
            error={error && Number.isNaN(Date.parse(effectiveAt)) ? error : undefined}
            label="Effective date and time"
            onChangeText={setEffectiveAt}
            value={effectiveAt}
          />
          <TextField label="Note (optional)" multiline onChangeText={setNote} value={note} />
          {error && !Number.isNaN(Date.parse(effectiveAt)) ? (
            <AppText style={{ color: colors.danger }} variant="error">
              {error}
            </AppText>
          ) : null}
          <Button icon={Check} label="Save status" loading={saving} onPress={submit} />
        </Pressable>
      </Pressable>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: { flex: 1, justifyContent: 'flex-end' },
  handle: {
    alignSelf: 'center',
    backgroundColor: '#66717D',
    borderRadius: 2,
    height: 4,
    width: 42,
  },
  header: { alignItems: 'center', flexDirection: 'row', justifyContent: 'space-between' },
  headerText: { flex: 1, gap: 3 },
  sheet: {
    borderTopLeftRadius: 8,
    borderTopRightRadius: 8,
    borderWidth: 1,
    gap: 18,
    padding: 18,
    paddingBottom: 28,
  },
});
