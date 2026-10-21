import { Redirect } from 'expo-router';

import { useAuth } from '@/auth/auth-provider';
import { AuthRecoveryScreen } from '@/components/auth-recovery';
import { Screen } from '@/components/screen';

export default function IndexScreen() {
  const { isLoading, session, status } = useAuth();

  if (isLoading) {
    return <Screen />;
  }

  if (status === 'recovering') {
    return <AuthRecoveryScreen />;
  }

  return <Redirect href={session ? '/(tabs)/home' : '/(auth)/sign-in'} />;
}
