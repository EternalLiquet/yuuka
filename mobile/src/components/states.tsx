import { AlertCircle, Inbox, RefreshCw, WifiOff } from 'lucide-react-native';
import type { ReactNode } from 'react';
import { StyleSheet, View } from 'react-native';

import { useMinimumVisibleDuration } from '@/hooks/use-minimum-visible-duration';
import { useAppTheme } from '@/theme/use-app-theme';

import { AppText } from './app-text';
import { Button } from './button';
import { YuukaMascot } from './yuuka-mascot';
import type { YuukaMascotVariant } from './yuuka-mascot';

type EmptyStateProps = {
  action?: ReactNode;
  mascot?: Extract<YuukaMascotVariant, 'clipboard' | 'idle' | 'wave'>;
  message: string;
  title: string;
};

export function EmptyState({ action, mascot, message, title }: EmptyStateProps) {
  const { colors } = useAppTheme();
  return (
    <View style={styles.state}>
      {mascot ? (
        <YuukaMascot
          playback="static"
          size={88}
          testID={`empty-state-mascot-${mascot}`}
          variant={mascot}
        />
      ) : (
        <Inbox color={colors.muted} size={28} />
      )}
      <AppText variant="label">{title}</AppText>
      <AppText style={{ color: colors.muted, textAlign: 'center' }} variant="caption">
        {message}
      </AppText>
      {action}
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

export function YuukaLoadingState({
  message = 'Loading...',
  minHeight = 220,
  size = 74,
}: {
  message?: string;
  minHeight?: number;
  size?: number;
}) {
  const { colors } = useAppTheme();
  return (
    <View
      accessibilityLabel={message}
      accessibilityLiveRegion="polite"
      accessibilityRole="progressbar"
      accessibilityState={{ busy: true }}
      style={[styles.state, { minHeight }]}
    >
      <YuukaMascot size={size} testID="yuuka-loading-mascot" />
      <AppText style={{ color: colors.muted, textAlign: 'center' }} variant="caption">
        {message}
      </AppText>
    </View>
  );
}

export function YuukaRefreshIndicator({
  minimumMs = 800,
  visible,
}: {
  minimumMs?: number;
  visible: boolean;
}) {
  const { colors } = useAppTheme();
  const shouldShow = useMinimumVisibleDuration(visible, minimumMs);
  if (!shouldShow) return null;

  return (
    <View
      accessibilityLabel="Refreshing data"
      accessibilityLiveRegion="polite"
      accessibilityRole="progressbar"
      accessibilityState={{ busy: true }}
      pointerEvents="none"
      style={[styles.refresh, { backgroundColor: colors.surface, borderColor: colors.border }]}
      testID="yuuka-refresh-indicator"
    >
      <YuukaMascot size={40} testID="yuuka-refresh-mascot" />
      <AppText style={{ color: colors.muted }} variant="caption">
        Refreshing...
      </AppText>
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
  refresh: {
    alignItems: 'center',
    alignSelf: 'flex-start',
    borderRadius: 999,
    borderWidth: 1,
    flexDirection: 'row',
    gap: 7,
    minHeight: 44,
    paddingHorizontal: 10,
    paddingVertical: 2,
  },
  state: {
    alignItems: 'center',
    gap: 10,
    justifyContent: 'center',
    minHeight: 220,
    padding: 24,
  },
});
