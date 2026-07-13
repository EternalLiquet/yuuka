import { Redirect, Tabs } from 'expo-router';
import { History, LayoutTemplate, RotateCcw, Settings, WalletCards } from 'lucide-react-native';
import type { LucideIcon } from 'lucide-react-native';
import { ColorValue } from 'react-native';

import { useAuth } from '@/auth/auth-provider';
import { Screen } from '@/components/screen';
import { useAppTheme } from '@/theme/use-app-theme';

function TabIcon({ icon: Icon, color }: { icon: LucideIcon; color: ColorValue }) {
  return <Icon color={String(color)} size={23} />;
}

export default function TabLayout() {
  const { colors } = useAppTheme();
  const { isLoading, session } = useAuth();

  if (isLoading) {
    return <Screen />;
  }

  if (!session) {
    return <Redirect href="/(auth)/sign-in" />;
  }

  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        sceneStyle: { backgroundColor: colors.background },
        tabBarActiveTintColor: colors.accent,
        tabBarInactiveTintColor: colors.muted,
        tabBarStyle: {
          backgroundColor: colors.surface,
          borderTopColor: colors.border,
        },
      }}
    >
      <Tabs.Screen
        name="active"
        options={{
          title: 'Active',
          tabBarIcon: ({ color }) => <TabIcon icon={WalletCards} color={color} />,
        }}
      />
      <Tabs.Screen
        name="history"
        options={{
          title: 'History',
          tabBarIcon: ({ color }) => <TabIcon icon={History} color={color} />,
        }}
      />
      <Tabs.Screen
        name="paybacks"
        options={{
          title: 'Paybacks',
          tabBarIcon: ({ color }) => <TabIcon icon={RotateCcw} color={color} />,
        }}
      />
      <Tabs.Screen
        name="templates"
        options={{
          title: 'Templates',
          tabBarIcon: ({ color }) => <TabIcon icon={LayoutTemplate} color={color} />,
        }}
      />
      <Tabs.Screen
        name="settings"
        options={{
          title: 'Settings',
          tabBarIcon: ({ color }) => <TabIcon icon={Settings} color={color} />,
        }}
      />
    </Tabs>
  );
}
