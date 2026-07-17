import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';

import type { RecurringBillPayload } from '@/api/use-yuuka-api';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { ErrorState, YuukaLoadingState } from '@/components/states';
import { RecurringBillEditor } from '@/features/recurring-bills/recurring-bill-editor';
import { useAppTheme } from '@/theme/use-app-theme';

export default function EditRecurringBillScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const api = useYuukaApi();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { colors } = useAppTheme();
  const query = useQuery({
    queryKey: ['recurring-bill', id],
    queryFn: () => api.recurringBill(id),
  });
  const mutation = useMutation({
    mutationFn: (payload: RecurringBillPayload & { version: number }) =>
      api.updateRecurringBill(id, payload),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['recurring-bill', id] }),
        queryClient.invalidateQueries({ queryKey: ['recurring-bills'] }),
      ]);
      router.back();
    },
  });
  if (query.isPending) return <YuukaLoadingState message="Loading recurring Bill..." />;
  if (query.isError || !query.data)
    return (
      <ErrorState message="The recurring Bill could not load." retry={() => query.refetch()} />
    );
  return (
    <>
      <Stack.Screen
        options={{
          headerShown: true,
          title: `Edit ${query.data.name}`,
          headerStyle: { backgroundColor: colors.surface },
          headerTintColor: colors.text,
        }}
      />
      <RecurringBillEditor
        definition={query.data}
        onSubmit={(payload) =>
          mutation.mutateAsync({ ...payload, version: query.data.version }).then(() => undefined)
        }
        submitLabel="Save recurring Bill"
      />
    </>
  );
}
