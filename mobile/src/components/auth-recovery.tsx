import { AlertCircle, LogOut, RefreshCw, WifiOff } from 'lucide-react-native';
import { useRef, useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { useAuth } from '@/auth/auth-provider';
import { useAppTheme } from '@/theme/use-app-theme';

import { AppText } from './app-text';
import { Button } from './button';
import { Screen } from './screen';

const NETWORK_MESSAGE =
  'Cannot connect to Yuuka. Turn on Tailscale, then return here and tap Retry. Your saved sign-in has not been removed.';
const GENERIC_MESSAGE =
  "Yuuka couldn't confirm your sign-in right now. Your saved sign-in has not been removed. Try again, or sign out.";
const CLEANUP_MESSAGE =
  'Saved sign-in data could not be removed. Your private data is locked, but sign-out is not complete. Keep Yuuka open and retry before closing the app.';

/**
 * Shown instead of any protected route while a saved sign-in cannot be
 * validated. It never reads or renders the unvalidated session.
 */
export function AuthRecoveryScreen() {
  const { colors } = useAppTheme();
  const { recovery, retryRecovery, signOut } = useAuth();
  const [signingOut, setSigningOut] = useState(false);
  const signingOutRef = useRef(false);
  const pending = Boolean(recovery?.pending);
  const network = recovery?.reason === 'network';
  const cleanup = recovery?.reason === 'signOut';
  const title = cleanup
    ? pending
      ? 'Signing out'
      : 'Sign-out incomplete'
    : network
      ? 'Cannot connect to Yuuka'
      : 'Sign-in not confirmed';
  const message = cleanup
    ? pending
      ? 'Removing saved sign-in data. Your private data is locked.'
      : CLEANUP_MESSAGE
    : network
      ? NETWORK_MESSAGE
      : GENERIC_MESSAGE;

  async function onSignOut() {
    if (signingOutRef.current) return;
    signingOutRef.current = true;
    setSigningOut(true);
    try {
      await signOut();
    } catch {
      // The provider keeps the cleanup failure and retry visible across navigation.
    } finally {
      signingOutRef.current = false;
      setSigningOut(false);
    }
  }

  return (
    <Screen contentContainerStyle={styles.screen}>
      <View style={styles.header}>
        {network ? (
          <WifiOff color={colors.processing} size={32} />
        ) : (
          <AlertCircle color={colors.danger} size={32} testID="recovery-alert-icon" />
        )}
        <AppText variant="headline">{title}</AppText>
        <AppText accessibilityLiveRegion="polite" style={{ color: colors.muted }}>
          {message}
        </AppText>
      </View>
      <View style={styles.actions}>
        <Button
          icon={RefreshCw}
          label={cleanup ? 'Retry sign out' : 'Retry'}
          loading={pending}
          onPress={() => void retryRecovery()}
        />
        {!cleanup && (
          <Button
            disabled={signingOut}
            icon={LogOut}
            label="Sign out"
            onPress={onSignOut}
            variant="secondary"
          />
        )}
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  actions: { gap: 12 },
  header: { alignItems: 'flex-start', gap: 12 },
  screen: { flex: 1, gap: 28, justifyContent: 'center', padding: 24 },
});
