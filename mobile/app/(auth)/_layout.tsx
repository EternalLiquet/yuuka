import { Redirect, Stack } from 'expo-router';

import { useAuth } from '@/auth/auth-provider';
import { AuthRecoveryScreen } from '@/components/auth-recovery';
import { Screen } from '@/components/screen';

export default function AuthLayout() {
  const { isLoading, session, status } = useAuth();

  if (isLoading) {
    return <Screen />;
  }

  if (status === 'recovering') {
    return <AuthRecoveryScreen />;
  }

  if (session) {
    return <Redirect href="/(tabs)/home" />;
  }

  return <Stack screenOptions={{ headerShown: false }} />;
}
