import { zodResolver } from '@hookform/resolvers/zod';
import { Controller, useForm } from 'react-hook-form';
import { useState } from 'react';
import {
  KeyboardAvoidingView,
  Platform,
  Pressable,
  StyleSheet,
  TextInput,
  View,
} from 'react-native';

import { useAuth } from '@/auth/auth-provider';
import { SignInFormValues, signInSchema } from '@/auth/schemas';
import { AppText } from '@/components/app-text';
import { Screen } from '@/components/screen';
import { normalizeApiBaseUrl } from '@/config/env';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

export default function SignInScreen() {
  const { colors } = useAppTheme();
  const { sessionExpired, signIn } = useAuth();
  const { settings, updateSettings } = useSettings();
  const [serverUrl, setServerUrl] = useState(settings.apiBaseUrl);
  const [serverError, setServerError] = useState('');
  const {
    control,
    formState: { errors, isSubmitting },
    handleSubmit,
    setError,
  } = useForm<SignInFormValues>({
    resolver: zodResolver(signInSchema),
    defaultValues: { email: '', password: '', totpCode: '' },
  });

  async function onSubmit(values: SignInFormValues) {
    try {
      await signIn(values);
    } catch (error) {
      setError('root', {
        message: error instanceof Error ? error.message : 'Unable to sign in.',
      });
    }
  }

  async function saveServer() {
    try {
      await updateSettings({ apiBaseUrl: normalizeApiBaseUrl(serverUrl) });
      setServerError('');
    } catch (error) {
      setServerError(error instanceof Error ? error.message : 'Enter a valid API URL.');
    }
  }

  return (
    <Screen contentContainerStyle={styles.screen}>
      <KeyboardAvoidingView
        behavior={Platform.select({ ios: 'padding', default: undefined })}
        style={styles.keyboard}
      >
        <View style={styles.header}>
          <AppText variant="eyebrow">Project Yuuka</AppText>
          <AppText variant="headline">Yuuka</AppText>
          {sessionExpired ? (
            <AppText style={{ color: colors.processing }} variant="caption">
              Your session expired. Sign in again.
            </AppText>
          ) : null}
        </View>

        <View
          style={[styles.form, { backgroundColor: colors.surface, borderColor: colors.border }]}
        >
          <Controller
            control={control}
            name="email"
            render={({ field: { onBlur, onChange, value } }) => (
              <View style={styles.field}>
                <AppText variant="label">Email</AppText>
                <TextInput
                  accessibilityLabel="Email"
                  autoCapitalize="none"
                  autoComplete="email"
                  keyboardType="email-address"
                  onBlur={onBlur}
                  onChangeText={onChange}
                  placeholder="you@example.com"
                  placeholderTextColor={colors.muted}
                  style={[
                    styles.input,
                    {
                      backgroundColor: colors.input,
                      borderColor: errors.email ? colors.danger : colors.border,
                      color: colors.text,
                    },
                  ]}
                  value={value}
                />
                {errors.email ? <AppText variant="error">{errors.email.message}</AppText> : null}
              </View>
            )}
          />

          <Controller
            control={control}
            name="password"
            render={({ field: { onBlur, onChange, value } }) => (
              <View style={styles.field}>
                <AppText variant="label">Password</AppText>
                <TextInput
                  accessibilityLabel="Password"
                  autoCapitalize="none"
                  autoComplete="password"
                  onBlur={onBlur}
                  onChangeText={onChange}
                  placeholder="Password"
                  placeholderTextColor={colors.muted}
                  secureTextEntry
                  textContentType="password"
                  style={[
                    styles.input,
                    {
                      backgroundColor: colors.input,
                      borderColor: errors.password ? colors.danger : colors.border,
                      color: colors.text,
                    },
                  ]}
                  value={value}
                />
                {errors.password ? (
                  <AppText variant="error">{errors.password.message}</AppText>
                ) : null}
              </View>
            )}
          />

          <Controller
            control={control}
            name="totpCode"
            render={({ field: { onBlur, onChange, value } }) => (
              <View style={styles.field}>
                <AppText variant="label">Authenticator code</AppText>
                <TextInput
                  accessibilityLabel="Authenticator code"
                  autoCapitalize="none"
                  autoComplete="one-time-code"
                  keyboardType="number-pad"
                  maxLength={6}
                  onBlur={onBlur}
                  onChangeText={onChange}
                  placeholder="000000"
                  placeholderTextColor={colors.muted}
                  style={[
                    styles.input,
                    {
                      backgroundColor: colors.input,
                      borderColor: errors.totpCode ? colors.danger : colors.border,
                      color: colors.text,
                    },
                  ]}
                  value={value}
                />
                {errors.totpCode ? (
                  <AppText variant="error">{errors.totpCode.message}</AppText>
                ) : null}
              </View>
            )}
          />

          {errors.root ? <AppText variant="error">{errors.root.message}</AppText> : null}

          <Pressable
            disabled={isSubmitting}
            onPress={handleSubmit(onSubmit)}
            style={({ pressed }) => [
              styles.button,
              {
                backgroundColor: colors.accent,
                opacity: pressed || isSubmitting ? 0.76 : 1,
              },
            ]}
          >
            <AppText style={[styles.buttonText, { color: colors.accentText }]}>
              {isSubmitting ? 'Signing in' : 'Sign in'}
            </AppText>
          </Pressable>

          <View style={[styles.server, { borderTopColor: colors.border }]}>
            <AppText variant="label">Server</AppText>
            <TextInput
              accessibilityLabel="Server URL"
              autoCapitalize="none"
              autoCorrect={false}
              onChangeText={setServerUrl}
              onEndEditing={saveServer}
              placeholder="https://yuuka.example/api/v1"
              placeholderTextColor={colors.muted}
              style={[
                styles.input,
                {
                  backgroundColor: colors.input,
                  borderColor: serverError ? colors.danger : colors.border,
                  color: colors.text,
                },
              ]}
              value={serverUrl}
            />
            {serverError ? <AppText variant="error">{serverError}</AppText> : null}
          </View>
        </View>
      </KeyboardAvoidingView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    justifyContent: 'center',
    padding: 24,
  },
  keyboard: {
    gap: 24,
  },
  header: {
    gap: 8,
  },
  form: {
    borderRadius: 8,
    borderWidth: 1,
    gap: 18,
    padding: 18,
  },
  field: {
    gap: 8,
  },
  input: {
    borderRadius: 8,
    borderWidth: 1,
    fontSize: 16,
    minHeight: 48,
    paddingHorizontal: 14,
  },
  server: {
    borderTopWidth: 1,
    gap: 8,
    paddingTop: 17,
  },
  button: {
    alignItems: 'center',
    borderRadius: 8,
    justifyContent: 'center',
    minHeight: 48,
    paddingHorizontal: 16,
  },
  buttonText: {
    fontSize: 16,
    fontWeight: '700',
  },
});
