import { z } from 'zod';

export const signInSchema = z.object({
  email: z.string().email('Enter a valid email address.'),
  password: z.string().min(8, 'Password must be at least 8 characters.'),
  totpCode: z
    .string()
    .regex(/^\d{6}$/, 'Enter the 6 digit authenticator code.')
    .optional()
    .or(z.literal('')),
});

export type SignInFormValues = z.infer<typeof signInSchema>;
