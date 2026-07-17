import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Stack, useRouter } from 'expo-router';

import type { RecurringBillPayload } from '@/api/use-yuuka-api';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { RecurringBillEditor } from '@/features/recurring-bills/recurring-bill-editor';
import { useAppTheme } from '@/theme/use-app-theme';

export default function NewRecurringBillScreen() {
  const api = useYuukaApi();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { colors } = useAppTheme();
  const mutation = useMutation({
    mutationFn: (payload: RecurringBillPayload) => api.createRecurringBill(payload),
    onSuccess: async (definition) => {
      await queryClient.invalidateQueries({ queryKey: ['recurring-bills'] });
      router.replace(`/recurring-bills/${definition.id}`);
    },
  });
  return (
    <>
      <Stack.Screen
        options={{
          headerShown: true,
          title: 'New Recurring Bill',
          headerStyle: { backgroundColor: colors.surface },
          headerTintColor: colors.text,
        }}
      />
      <RecurringBillEditor
        onSubmit={(payload) => mutation.mutateAsync(payload).then(() => undefined)}
        submitLabel="Create recurring Bill"
      />
    </>
  );
}
