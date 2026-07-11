import { Redirect } from 'expo-router';

import { useAuth } from '@/auth/auth-provider';
import { Screen } from '@/components/screen';

export default function IndexScreen() {
  const { isLoading, session } = useAuth();

  if (isLoading) {
    return <Screen />;
  }

  return <Redirect href={session ? '/(tabs)/active' : '/(auth)/sign-in'} />;
}
