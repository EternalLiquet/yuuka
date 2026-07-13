import { PropsWithChildren } from 'react';
import { StyleProp, StyleSheet, Text, TextProps, TextStyle } from 'react-native';

import { useAppTheme } from '@/theme/use-app-theme';

type AppTextProps = PropsWithChildren<
  Omit<TextProps, 'style'> & {
    numberOfLines?: number;
    style?: StyleProp<TextStyle>;
    variant?: 'body' | 'caption' | 'eyebrow' | 'error' | 'headline' | 'label' | 'money' | 'title';
  }
>;

export function AppText({
  children,
  numberOfLines,
  style,
  variant = 'body',
  ...props
}: AppTextProps) {
  const { colors } = useAppTheme();

  return (
    <Text
      {...props}
      numberOfLines={numberOfLines}
      style={[styles.base, { color: colors.text }, styles[variant], style]}
    >
      {children}
    </Text>
  );
}

const styles = StyleSheet.create({
  base: {
    letterSpacing: 0,
  },
  body: {
    fontSize: 16,
    lineHeight: 24,
  },
  caption: {
    fontSize: 13,
    lineHeight: 18,
  },
  eyebrow: {
    fontSize: 13,
    fontWeight: '700',
    lineHeight: 18,
    textTransform: 'uppercase',
  },
  error: {
    fontSize: 13,
    lineHeight: 18,
  },
  headline: {
    fontSize: 40,
    fontWeight: '800',
    lineHeight: 46,
  },
  label: {
    fontSize: 14,
    fontWeight: '700',
    lineHeight: 20,
  },
  money: {
    fontFamily: 'monospace',
    fontSize: 18,
    fontWeight: '700',
    lineHeight: 24,
  },
  title: {
    fontSize: 26,
    fontWeight: '800',
    lineHeight: 32,
  },
});
