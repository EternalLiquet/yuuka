import { AlertCircle, Inbox, RefreshCw, WifiOff } from 'lucide-react-native';
import { StyleSheet, View } from 'react-native';

import { useAppTheme } from '@/theme/use-app-theme';

import { AppText } from './app-text';
import { Button } from './button';

export function EmptyState({ message, title }: { message: string; title: string }) {
  const { colors } = useAppTheme();
  return (
    <View style={styles.state}>
      <Inbox color={colors.muted} size={28} />
      <AppText variant="label">{title}</AppText>
      <AppText style={{ color: colors.muted, textAlign: 'center' }} variant="caption">
        {message}
      </AppText>
    </View>
  );
}

export function ErrorState({ message, retry }: { message: string; retry: () => void }) {
  const { colors } = useAppTheme();
  return (
    <View style={styles.state}>
      <AlertCircle color={colors.danger} size={28} />
      <AppText variant="label">Could not load</AppText>
      <AppText style={{ color: colors.muted, textAlign: 'center' }} variant="caption">
        {message}
      </AppText>
      <Button icon={RefreshCw} label="Retry" onPress={retry} variant="secondary" />
    </View>
  );
}

export function StaleBanner() {
  const { colors } = useAppTheme();
  return (
    <View style={[styles.banner, { backgroundColor: colors.processingSoft }]}>
      <WifiOff color={colors.processing} size={17} />
      <AppText style={{ color: colors.processing }} variant="caption">
        Showing saved data. Reconnect to refresh.
      </AppText>
    </View>
  );
}

const styles = StyleSheet.create({
  banner: {
    alignItems: 'center',
    borderRadius: 6,
    flexDirection: 'row',
    gap: 8,
    paddingHorizontal: 11,
    paddingVertical: 9,
  },
  state: {
    alignItems: 'center',
    gap: 10,
    justifyContent: 'center',
    minHeight: 220,
    padding: 24,
  },
});
