/** Wraps secure-storage failures so they are never mistaken for bad credentials. */
export class AuthStorageError extends Error {
  constructor(operation: 'read' | 'write' | 'clear') {
    super(`Saved sign-in could not be ${operation === 'read' ? 'read' : 'updated'}.`);
    this.name = 'AuthStorageError';
  }
}
