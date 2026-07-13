import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';

import { useYuukaApi } from '@/api/use-yuuka-api';
import { PaybackEditor } from '@/features/paybacks/payback-editor';

export default function NewPaybackScreen() {
  const api = useYuukaApi();
  const router = useRouter();
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: api.createPayback,
    onSuccess: async (payback) => {
      await queryClient.invalidateQueries({ queryKey: ['paybacks'] });
      router.replace(`/paybacks/${payback.id}`);
    },
  });

  return (
    <PaybackEditor
      onClose={() => router.back()}
      onSubmit={(values) => mutation.mutateAsync(values).then(() => undefined)}
    />
  );
}
