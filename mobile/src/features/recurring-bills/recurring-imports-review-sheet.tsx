import { ChevronRight, X } from 'lucide-react-native';
import { Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';

import type { RecurringBillOccurrence } from '@/api/contracts';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { statusLabel } from '@/features/recurring-bills/coverage';
import { useAppTheme } from '@/theme/use-app-theme';

export function RecurringImportsReviewSheet({
  item,
  onClose,
  onOpen,
}: {
  item: RecurringBillOccurrence | null;
  onClose: () => void;
  onOpen: (paycheckId: string, entryId: string) => void;
}) {
  const { colors } = useAppTheme();
  return (
    <Modal animationType="fade" onRequestClose={onClose} transparent visible={Boolean(item)}>
      <View style={styles.backdrop}>
        <View style={[styles.sheet, { backgroundColor: colors.surface }]}>
          <View style={[styles.header, { borderBottomColor: colors.border }]}>
            <View style={styles.headerText}>
              <AppText variant="title">Review imports</AppText>
              <AppText style={{ color: colors.muted }} variant="caption">
                {item?.name} was added to {item?.imports.length ?? 0} paychecks.
              </AppText>
            </View>
            <Pressable
              accessibilityLabel="Close import review"
              accessibilityRole="button"
              onPress={onClose}
              style={styles.headerClose}
            >
              <X color={colors.text} size={22} />
            </Pressable>
          </View>
          <ScrollView
            contentContainerStyle={styles.list}
            keyboardShouldPersistTaps="handled"
            testID="recurring-import-review-list"
          >
            {item?.imports.map((imported) => (
              <Pressable
                accessibilityHint="Opens the imported entry in its paycheck"
                accessibilityLabel={`Open ${item.name} in ${imported.paycheckName}, ${statusLabel(imported.status)}`}
                accessibilityRole="button"
                key={imported.entryId}
                onPress={() => onOpen(imported.paycheckId, imported.entryId)}
                style={({ pressed }) => [
                  styles.row,
                  { borderBottomColor: colors.border },
                  pressed && styles.pressed,
                ]}
              >
                <View style={styles.rowText}>
                  <AppText variant="label">{imported.paycheckName}</AppText>
                  <AppText style={{ color: colors.muted }} variant="caption">
                    {statusLabel(imported.status)}
                  </AppText>
                </View>
                <ChevronRight color={colors.muted} size={18} />
              </Pressable>
            ))}
          </ScrollView>
          <View style={[styles.footer, { borderTopColor: colors.border }]}>
            <Button label="Close" onPress={onClose} variant="secondary" />
          </View>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: { backgroundColor: 'rgba(0,0,0,0.6)', flex: 1, justifyContent: 'center', padding: 24 },
  footer: { borderTopWidth: 1, padding: 14 },
  header: {
    alignItems: 'center',
    borderBottomWidth: 1,
    flexDirection: 'row',
    gap: 12,
    padding: 14,
  },
  headerClose: { alignItems: 'center', justifyContent: 'center', minHeight: 44, minWidth: 44 },
  headerText: { flex: 1, gap: 3, minWidth: 0 },
  list: { paddingHorizontal: 14 },
  pressed: { opacity: 0.72 },
  row: {
    alignItems: 'center',
    borderBottomWidth: 1,
    flexDirection: 'row',
    gap: 10,
    minHeight: 60,
    paddingVertical: 10,
  },
  rowText: { flex: 1, gap: 3, minWidth: 0 },
  sheet: { borderRadius: 10, maxHeight: '82%', minHeight: 0, overflow: 'hidden' },
});
