import { Redirect, Tabs } from 'expo-router';
import {
  ClipboardList,
  Home,
  History,
  LayoutTemplate,
  PiggyBank,
  RotateCcw,
  Settings,
  WalletCards,
} from 'lucide-react-native';
import type { LucideIcon } from 'lucide-react-native';
import { ColorValue } from 'react-native';

import { useAuth } from '@/auth/auth-provider';
import { Screen } from '@/components/screen';
import { AppMenuButton } from '@/components/app-menu';
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
        headerShown: true,
        headerLeft: () => <AppMenuButton />,
        headerStyle: { backgroundColor: colors.surface },
        headerTintColor: colors.text,
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
          tabBarAccessibilityLabel: 'Active tab',
          tabBarIcon: ({ color }) => <TabIcon icon={WalletCards} color={color} />,
        }}
      />
      <Tabs.Screen
        name="paybacks"
        options={{
          title: 'Paybacks',
          tabBarAccessibilityLabel: 'Paybacks tab',
          tabBarIcon: ({ color }) => <TabIcon icon={RotateCcw} color={color} />,
        }}
      />
      <Tabs.Screen
        name="home"
        options={{
          title: 'Home',
          tabBarAccessibilityLabel: 'Home tab',
          tabBarIcon: ({ color }) => <TabIcon icon={Home} color={color} />,
        }}
      />
      <Tabs.Screen
        name="planned-savings"
        options={{
          title: 'Planned Savings',
          tabBarAccessibilityLabel: 'Planned Savings tab',
          tabBarIcon: ({ color }) => <TabIcon icon={PiggyBank} color={color} />,
        }}
      />
      <Tabs.Screen
        name="history"
        options={{
          title: 'History',
          tabBarAccessibilityLabel: 'History tab',
          tabBarIcon: ({ color }) => <TabIcon icon={History} color={color} />,
        }}
      />
      <Tabs.Screen
        name="expense-ledgers"
        options={{
          title: 'Expense Lists',
          href: null,
          tabBarAccessibilityLabel: 'Expense Lists tab',
          tabBarIcon: ({ color }) => <TabIcon icon={ClipboardList} color={color} />,
        }}
      />
      <Tabs.Screen
        name="templates"
        options={{
          title: 'Templates',
          href: null,
          tabBarAccessibilityLabel: 'Templates tab',
          tabBarIcon: ({ color }) => <TabIcon icon={LayoutTemplate} color={color} />,
        }}
      />
      <Tabs.Screen
        name="settings"
        options={{
          title: 'Settings',
          href: null,
          tabBarAccessibilityLabel: 'Settings tab',
          tabBarIcon: ({ color }) => <TabIcon icon={Settings} color={color} />,
        }}
      />
    </Tabs>
  );
}
