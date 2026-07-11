import { forwardRef } from 'react';
import {
  KeyboardTypeOptions,
  StyleProp,
  StyleSheet,
  TextInput,
  TextInputProps,
  View,
  ViewStyle,
} from 'react-native';

import { useAppTheme } from '@/theme/use-app-theme';

import { AppText } from './app-text';

type TextFieldProps = Omit<TextInputProps, 'style'> & {
  containerStyle?: StyleProp<ViewStyle>;
  error?: string;
  keyboardType?: KeyboardTypeOptions;
  label: string;
};

export const TextField = forwardRef<TextInput, TextFieldProps>(function TextField(
  { containerStyle, error, label, multiline, ...props },
  ref,
) {
  const { colors } = useAppTheme();
  return (
    <View style={[styles.field, containerStyle]}>
      <AppText variant="label">{label}</AppText>
      <TextInput
        {...props}
        accessibilityLabel={props.accessibilityLabel ?? label}
        multiline={multiline}
        placeholderTextColor={colors.muted}
        ref={ref}
        style={[
          styles.input,
          multiline && styles.multiline,
          {
            backgroundColor: colors.input,
            borderColor: error ? colors.danger : colors.border,
            color: colors.text,
          },
        ]}
      />
      {error ? (
        <AppText style={{ color: colors.danger }} variant="error">
          {error}
        </AppText>
      ) : null}
    </View>
  );
});

const styles = StyleSheet.create({
  field: { gap: 7 },
  input: {
    borderRadius: 8,
    borderWidth: 1,
    fontSize: 16,
    minHeight: 46,
    paddingHorizontal: 13,
    paddingVertical: 10,
  },
  multiline: { minHeight: 88, textAlignVertical: 'top' },
});
