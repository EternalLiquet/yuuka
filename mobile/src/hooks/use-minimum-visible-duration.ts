import { useEffect, useRef, useState } from 'react';

export function useMinimumVisibleDuration(visible: boolean, minimumMs: number) {
  const startedAt = useRef<number | null>(null);
  const [shouldShow, setShouldShow] = useState(visible);

  useEffect(() => {
    if (visible) {
      if (startedAt.current === null) {
        startedAt.current = Date.now();
      }
      // A new visible cycle must be captured before a fast completion can render it away.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setShouldShow(true);
      return;
    }

    if (startedAt.current === null) {
      return;
    }

    const remaining = Math.max(0, minimumMs - (Date.now() - startedAt.current));
    const timer = setTimeout(() => {
      startedAt.current = null;
      setShouldShow(false);
    }, remaining);
    return () => clearTimeout(timer);
  }, [minimumMs, visible]);

  return shouldShow;
}
