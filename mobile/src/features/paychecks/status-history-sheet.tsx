import { useQuery } from '@tanstack/react-query';
import { X } from 'lucide-react-native';
import { Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { Entry } from '@/api/contracts';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { StatusBadge } from '@/components/status-badge';
import { useAppTheme } from '@/theme/use-app-theme';

export function StatusHistorySheet({
  entry,
  onClose,
}: {
  entry: Entry | null;
  onClose: () => void;
}) {
  const api = useYuukaApi();
  const { colors } = useAppTheme();
  const query = useQuery({
    queryKey: ['entry', entry?.id, 'status-history'],
    queryFn: () => api.statusHistory(entry!.id),
    enabled: Boolean(entry),
  });
  return (
    <Modal animationType="slide" onRequestClose={onClose} visible={Boolean(entry)}>
      <View style={[styles.screen, { backgroundColor: colors.background }]}>
        <View style={[styles.header, { borderBottomColor: colors.border }]}>
          <View style={styles.headerText}>
            <AppText variant="title">Status history</AppText>
            <AppText style={{ color: colors.muted }} variant="caption">
              {entry?.name}
            </AppText>
          </View>
          <Pressable
            accessibilityLabel="Close status history"
            onPress={onClose}
            style={styles.close}
          >
            <X color={colors.text} size={23} />
          </Pressable>
        </View>
        <ScrollView contentContainerStyle={styles.list}>
          {query.data?.items.map((event) => (
            <View key={event.id} style={[styles.event, { borderColor: colors.border }]}>
              <View style={styles.transition}>
                {event.fromStatus ? (
                  <StatusBadge status={event.fromStatus} />
                ) : (
                  <AppText style={{ color: colors.muted }} variant="caption">
                    Created
                  </AppText>
                )}
                <AppText style={{ color: colors.muted }} variant="caption">
                  to
                </AppText>
                <StatusBadge status={event.toStatus} />
              </View>
              <AppText variant="caption">Effective {formatDateTime(event.effectiveAt)}</AppText>
              <AppText style={{ color: colors.muted }} variant="caption">
                Recorded {formatDateTime(event.recordedAt)}
              </AppText>
              {event.note ? (
                <AppText style={{ color: colors.muted }} variant="caption">
                  {event.note}
                </AppText>
              ) : null}
            </View>
          ))}
          {query.isError ? (
            <AppText style={{ color: colors.danger }} variant="error">
              {query.error instanceof Error ? query.error.message : 'History could not be loaded.'}
            </AppText>
          ) : null}
        </ScrollView>
      </View>
    </Modal>
  );
}

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(value),
  );
}

const styles = StyleSheet.create({
  close: { alignItems: 'center', height: 44, justifyContent: 'center', width: 44 },
  event: { borderLeftWidth: 2, gap: 7, paddingLeft: 12, paddingVertical: 9 },
  header: {
    alignItems: 'center',
    borderBottomWidth: 1,
    flexDirection: 'row',
    justifyContent: 'space-between',
    padding: 18,
  },
  headerText: { flex: 1, gap: 3 },
  list: { gap: 10, padding: 18 },
  screen: { flex: 1 },
  transition: { alignItems: 'center', flexDirection: 'row', flexWrap: 'wrap', gap: 7 },
});
