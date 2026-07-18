import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Stack, useRouter } from 'expo-router';

import { useYuukaApi } from '@/api/use-yuuka-api';
import { SinkingFundEditor } from '@/features/sinking-funds/sinking-fund-editor';

export default function NewSinkingFundScreen() {
  const api = useYuukaApi();
  const router = useRouter();
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: api.createSinkingFund,
    onSuccess: async (sinkingFund) => {
      await queryClient.invalidateQueries({ queryKey: ['sinking-funds'] });
      router.replace(`/sinking-funds/${sinkingFund.id}`);
    },
  });

  return (
    <>
      <Stack.Screen options={{ headerShown: false }} />
      <SinkingFundEditor
        onClose={() => router.back()}
        onSubmit={(values) => mutation.mutateAsync(values).then(() => undefined)}
      />
    </>
  );
}
