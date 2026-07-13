import TestRenderer, { act } from 'react-test-renderer';
import { Text } from 'react-native';

import { useMinimumVisibleDuration } from '@/hooks/use-minimum-visible-duration';

describe('minimum visible duration', () => {
  beforeEach(() => {
    jest.useFakeTimers();
  });

  afterEach(() => {
    jest.clearAllTimers();
    jest.useRealTimers();
    jest.restoreAllMocks();
  });

  it('keeps a fast cold load visible for one second total', () => {
    const renderer = renderProbe(true);

    act(() => jest.advanceTimersByTime(100));
    updateProbe(renderer, false);
    expect(isVisible(renderer)).toBe(true);

    act(() => jest.advanceTimersByTime(899));
    expect(isVisible(renderer)).toBe(true);
    act(() => jest.advanceTimersByTime(1));
    expect(isVisible(renderer)).toBe(false);
    unmount(renderer);
  });

  it('does not add another second after a slow load', () => {
    const setTimeoutSpy = jest.spyOn(globalThis, 'setTimeout');
    const renderer = renderProbe(true);

    act(() => jest.advanceTimersByTime(2000));
    updateProbe(renderer, false);
    act(() => jest.advanceTimersByTime(0));

    expect(isVisible(renderer)).toBe(false);
    expect(setTimeoutSpy).toHaveBeenCalledWith(expect.any(Function), 0);
    unmount(renderer);
  });

  it('bypasses the timer when cached data makes the initial state not loading', () => {
    const setTimeoutSpy = jest.spyOn(globalThis, 'setTimeout');
    const renderer = renderProbe(false);

    expect(isVisible(renderer)).toBe(false);
    expect(setTimeoutSpy).not.toHaveBeenCalled();
    unmount(renderer);
  });

  it('does not duplicate a pending timer and clears it on unmount', () => {
    const setTimeoutSpy = jest.spyOn(globalThis, 'setTimeout');
    const clearTimeoutSpy = jest.spyOn(globalThis, 'clearTimeout');
    const renderer = renderProbe(true);

    act(() => jest.advanceTimersByTime(100));
    updateProbe(renderer, false);
    updateProbe(renderer, false);
    expect(setTimeoutSpy).toHaveBeenCalledTimes(1);

    unmount(renderer);
    expect(clearTimeoutSpy).toHaveBeenCalledTimes(1);
  });
});

function Probe({ loading }: { loading: boolean }) {
  const visible = useMinimumVisibleDuration(loading, 1000);
  return <Text testID="visibility">{visible ? 'visible' : 'hidden'}</Text>;
}

function renderProbe(loading: boolean) {
  let renderer: TestRenderer.ReactTestRenderer | undefined;
  act(() => {
    renderer = TestRenderer.create(<Probe loading={loading} />);
  });
  if (!renderer) throw new Error('Renderer was not created.');
  return renderer;
}

function updateProbe(renderer: TestRenderer.ReactTestRenderer, loading: boolean) {
  act(() => renderer.update(<Probe loading={loading} />));
}

function isVisible(renderer: TestRenderer.ReactTestRenderer) {
  return renderer.root.findByProps({ testID: 'visibility' }).props.children === 'visible';
}

function unmount(renderer: TestRenderer.ReactTestRenderer) {
  act(() => renderer.unmount());
}
