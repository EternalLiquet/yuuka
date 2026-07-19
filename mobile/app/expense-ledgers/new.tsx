import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { ArrowLeft, Save } from 'lucide-react-native';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { displayError } from '@/api/display-error';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { IconButton } from '@/components/icon-button';
import { ScrollScreen } from '@/components/scroll-screen';
import { TextField } from '@/components/text-field';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

export default function NewExpenseLedgerScreen() {
  const api = useYuukaApi();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const [name, setName] = useState('');
  const [notes, setNotes] = useState('');
  const mutation = useMutation({
    mutationFn: () => api.createExpenseLedger({ name: name.trim(), notes: notes.trim() || null }),
    onSuccess: async (ledger) => {
      await queryClient.invalidateQueries({ queryKey: ['expense-ledgers'] });
      router.replace(`/expense-ledgers/${ledger.id}`);
    },
  });
  const canSave = name.trim().length > 0;

  return (
    <ScrollScreen contentContainerStyle={styles.content}>
      <View style={styles.header}>
        <IconButton icon={ArrowLeft} label="Back" onPress={() => router.back()} />
        <AppText variant="title">New Expense List</AppText>
      </View>
      <TextField label="Name" onChangeText={setName} value={name} />
      <TextField
        label="Notes"
        multiline
        onChangeText={setNotes}
        textAlignVertical="top"
        value={notes}
      />
      {mutation.error ? (
        <AppText style={{ color: colors.danger }} variant="error">
          {displayError(mutation.error, settings.currencyCode, 'Expense List was not saved.')}
        </AppText>
      ) : null}
      <Button
        disabled={!canSave}
        icon={Save}
        label="Save Expense List"
        loading={mutation.isPending}
        onPress={() => mutation.mutate()}
      />
    </ScrollScreen>
  );
}

const styles = StyleSheet.create({
  content: { gap: 14 },
  header: { alignItems: 'center', flexDirection: 'row', gap: 12 },
});
