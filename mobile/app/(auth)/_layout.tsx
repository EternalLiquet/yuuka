import { Redirect, Stack } from 'expo-router';

import { useAuth } from '@/auth/auth-provider';
import { Screen } from '@/components/screen';

export default function AuthLayout() {
  const { isLoading, session } = useAuth();

  if (isLoading) {
    return <Screen />;
  }

  if (session) {
    return <Redirect href="/(tabs)/active" />;
  }

  return <Stack screenOptions={{ headerShown: false }} />;
}
