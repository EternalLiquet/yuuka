import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Stack, useRouter } from 'expo-router';
import { Save } from 'lucide-react-native';
import { useRef, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { StyleSheet, View } from 'react-native';

import { displayError } from '@/api/display-error';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { ScrollScreen } from '@/components/scroll-screen';
import { TextField } from '@/components/text-field';
import { TemplateFormValues, templateFormSchema } from '@/features/templates/form-schemas';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

export default function NewTemplateScreen() {
  const api = useYuukaApi();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const submitInFlight = useRef(false);
  const [submitLocked, setSubmitLocked] = useState(false);
  const {
    control,
    formState: { errors, isSubmitting },
    handleSubmit,
    setError,
  } = useForm<TemplateFormValues>({
    resolver: zodResolver(templateFormSchema),
    defaultValues: { name: '', description: '' },
  });

  const mutation = useMutation({
    mutationFn: (values: TemplateFormValues) =>
      api.createTemplate({
        name: values.name.trim(),
        description: values.description.trim() || null,
        entries: [],
      }),
    onSuccess: async (template) => {
      await queryClient.invalidateQueries({ queryKey: ['templates'] });
      router.replace(`/templates/${template.id}`);
    },
    onSettled: () => {
      submitInFlight.current = false;
      setSubmitLocked(false);
    },
  });

  async function submit(values: TemplateFormValues) {
    if (submitInFlight.current || submitLocked || mutation.isPending) return;

    submitInFlight.current = true;
    setSubmitLocked(true);

    try {
      await mutation.mutateAsync(values);
    } catch (error) {
      submitInFlight.current = false;
      setSubmitLocked(false);
      setError('root', {
        message: displayError(error, settings.currencyCode, 'The template was not created.'),
      });
    }
  }

  return (
    <>
      <Stack.Screen
        options={{
          headerShown: true,
          title: 'New Template',
          headerStyle: { backgroundColor: colors.surface },
          headerTintColor: colors.text,
        }}
      />
      <ScrollScreen contentContainerStyle={styles.content}>
        <View style={styles.form}>
          <Field control={control} error={errors.name?.message} label="Name" name="name" />
          <Field control={control} label="Description (optional)" multiline name="description" />
        </View>

        {errors.root?.message ? (
          <AppText style={{ color: colors.danger }} variant="error">
            {errors.root.message}
          </AppText>
        ) : null}
        <Button
          icon={Save}
          label="Create template"
          loading={isSubmitting || submitLocked || mutation.isPending}
          // eslint-disable-next-line react-hooks/refs
          onPress={handleSubmit(submit)}
        />
      </ScrollScreen>
    </>
  );
}

function Field({
  control,
  error,
  label,
  multiline,
  name,
}: {
  control: ReturnType<typeof useForm<TemplateFormValues>>['control'];
  error?: string;
  label: string;
  multiline?: boolean;
  name: keyof TemplateFormValues;
}) {
  return (
    <Controller
      control={control}
      name={name}
      render={({ field }) => (
        <TextField
          error={error}
          label={label}
          multiline={multiline}
          onBlur={field.onBlur}
          onChangeText={field.onChange}
          value={String(field.value ?? '')}
        />
      )}
    />
  );
}

const styles = StyleSheet.create({
  content: { gap: 20, paddingBottom: 36 },
  form: { gap: 16 },
});
