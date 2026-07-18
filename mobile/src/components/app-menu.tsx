import type { Href } from 'expo-router';
import { useRouter } from 'expo-router';
import {
  History,
  LayoutTemplate,
  Menu,
  PiggyBank,
  ReceiptText,
  RotateCcw,
  Settings,
  WalletCards,
  X,
} from 'lucide-react-native';
import type { LucideIcon } from 'lucide-react-native';
import { useState } from 'react';
import { Modal, Pressable, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { AppText } from '@/components/app-text';
import { useAppTheme } from '@/theme/use-app-theme';

const destinations: { href: Href; icon: LucideIcon; label: string }[] = [
  { href: '/(tabs)/active', icon: WalletCards, label: 'Active' },
  { href: '/(tabs)/history', icon: History, label: 'History' },
  { href: '/(tabs)/paybacks', icon: RotateCcw, label: 'Paybacks' },
  { href: '/sinking-funds', icon: PiggyBank, label: 'Sinking Funds' },
  { href: '/(tabs)/templates', icon: LayoutTemplate, label: 'Templates' },
  { href: '/recurring-bills', icon: ReceiptText, label: 'Recurring Bills' },
  { href: '/(tabs)/settings', icon: Settings, label: 'Settings' },
];

export function AppMenuButton() {
  const { colors } = useAppTheme();
  const router = useRouter();
  const [open, setOpen] = useState(false);

  function navigate(href: Href) {
    setOpen(false);
    router.push(href);
  }

  return (
    <>
      <Pressable
        accessibilityLabel="Open app menu"
        accessibilityRole="button"
        hitSlop={8}
        onPress={() => setOpen(true)}
        style={styles.trigger}
      >
        <Menu color={colors.text} size={25} />
      </Pressable>
      <Modal animationType="fade" onRequestClose={() => setOpen(false)} transparent visible={open}>
        <View style={styles.overlay}>
          <SafeAreaView style={[styles.drawer, { backgroundColor: colors.surface }]}>
            <View style={[styles.header, { borderBottomColor: colors.border }]}>
              <AppText variant="title">Yuuka</AppText>
              <Pressable
                accessibilityLabel="Close app menu"
                accessibilityRole="button"
                onPress={() => setOpen(false)}
                style={styles.close}
              >
                <X color={colors.text} size={23} />
              </Pressable>
            </View>
            <View accessibilityLabel="App destinations" style={styles.destinations}>
              {destinations.map(({ href, icon: Icon, label }) => (
                <Pressable
                  accessibilityLabel={`Open ${label}`}
                  accessibilityRole="button"
                  key={label}
                  onPress={() => navigate(href)}
                  style={({ pressed }) => [
                    styles.destination,
                    { borderColor: colors.border },
                    pressed && styles.pressed,
                  ]}
                >
                  <Icon color={colors.accent} size={22} />
                  <AppText variant="label">{label}</AppText>
                </Pressable>
              ))}
            </View>
          </SafeAreaView>
          <Pressable
            accessibilityLabel="Close app menu backdrop"
            onPress={() => setOpen(false)}
            style={styles.backdrop}
          />
        </View>
      </Modal>
    </>
  );
}

const styles = StyleSheet.create({
  backdrop: { flex: 1 },
  close: { alignItems: 'center', height: 44, justifyContent: 'center', width: 44 },
  destination: {
    alignItems: 'center',
    borderBottomWidth: 1,
    flexDirection: 'row',
    gap: 14,
    minHeight: 56,
    paddingHorizontal: 18,
  },
  destinations: { paddingTop: 8 },
  drawer: { height: '100%', width: '82%', maxWidth: 360 },
  header: {
    alignItems: 'center',
    borderBottomWidth: 1,
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingHorizontal: 18,
    paddingVertical: 10,
  },
  overlay: { backgroundColor: 'rgba(0,0,0,0.55)', flex: 1, flexDirection: 'row' },
  pressed: { opacity: 0.72 },
  trigger: { alignItems: 'center', height: 44, justifyContent: 'center', marginLeft: 8, width: 44 },
});
