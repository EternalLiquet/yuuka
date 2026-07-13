import TestRenderer, { act } from 'react-test-renderer';
import type { ReactElement } from 'react';
import { AccessibilityInfo } from 'react-native';

import { YuukaLoadingState } from '@/components/states';
import { YuukaMascot } from '@/components/yuuka-mascot';

jest.mock('@/settings/settings-provider', () => ({
  useSettings: () => ({
    settings: {
      apiBaseUrl: 'http://localhost:8080/api/v1',
      currencyCode: 'USD',
      theme: 'dark',
      timezone: 'America/Indianapolis',
    },
  }),
}));

describe('Yuuka mascot loading animation', () => {
  let reduceMotionHandler: (enabled: boolean) => void;
  let remove: jest.Mock;

  beforeEach(() => {
    jest.useFakeTimers();
    remove = jest.fn();
    jest
      .spyOn(AccessibilityInfo, 'isReduceMotionEnabled')
      .mockImplementation(() => new Promise(() => undefined));
    jest.spyOn(AccessibilityInfo, 'addEventListener').mockImplementation(((
      _,
      handler: (enabled: boolean) => void,
    ) => {
      reduceMotionHandler = handler;
      return { remove } as unknown as ReturnType<typeof AccessibilityInfo.addEventListener>;
    }) as typeof AccessibilityInfo.addEventListener);
  });

  afterEach(() => {
    jest.clearAllTimers();
    jest.useRealTimers();
    jest.restoreAllMocks();
  });

  it('renders the initial running frame and advances while playing', () => {
    const renderer = renderTree(<YuukaMascot testID="mascot" />);
    const firstSource = mascot(renderer).props.source;

    act(() => jest.advanceTimersByTime(100));

    expect(mascot(renderer).props.source).not.toBe(firstSource);
    unmount(renderer);
  });

  it('loops after the final running frame', () => {
    const renderer = renderTree(<YuukaMascot testID="mascot" />);
    const firstSource = mascot(renderer).props.source;

    act(() => jest.advanceTimersByTime(800));

    expect(mascot(renderer).props.source).toBe(firstSource);
    unmount(renderer);
  });

  it('does not advance when paused', () => {
    const renderer = renderTree(<YuukaMascot playing={false} testID="mascot" />);
    const firstSource = mascot(renderer).props.source;

    act(() => jest.advanceTimersByTime(500));

    expect(mascot(renderer).props.source).toBe(firstSource);
    unmount(renderer);
  });

  it('clears timers on unmount and avoids duplicate intervals on rerender', () => {
    const setIntervalSpy = jest.spyOn(globalThis, 'setInterval');
    const clearIntervalSpy = jest.spyOn(globalThis, 'clearInterval');
    const renderer = renderTree(<YuukaMascot size={60} testID="mascot" />);

    act(() => renderer.update(<YuukaMascot size={72} testID="mascot" />));
    expect(setIntervalSpy).toHaveBeenCalledTimes(1);
    expect(mascot(renderer).props.style).toEqual(
      expect.arrayContaining([expect.objectContaining({ height: 72, width: 72 })]),
    );

    unmount(renderer);
    expect(clearIntervalSpy).toHaveBeenCalledTimes(1);
    expect(remove).toHaveBeenCalled();
  });

  it('starts and stops animation when playing changes', () => {
    const renderer = renderTree(<YuukaMascot playing={false} testID="mascot" />);
    const firstSource = mascot(renderer).props.source;

    act(() => renderer.update(<YuukaMascot playing testID="mascot" />));
    act(() => jest.advanceTimersByTime(100));
    expect(mascot(renderer).props.source).not.toBe(firstSource);

    act(() => renderer.update(<YuukaMascot playing={false} testID="mascot" />));
    const pausedSource = mascot(renderer).props.source;
    act(() => jest.advanceTimersByTime(300));
    expect(mascot(renderer).props.source).toBe(pausedSource);
    unmount(renderer);
  });

  it('renders a static decorative frame when reduced motion is enabled', () => {
    const renderer = renderTree(<YuukaMascot testID="mascot" />);

    act(() => reduceMotionHandler(true));
    const firstSource = mascot(renderer).props.source;
    act(() => jest.advanceTimersByTime(500));

    expect(mascot(renderer).props.source).toBe(firstSource);
    expect(mascot(renderer).props.accessible).toBe(false);
    expect(mascot(renderer).props.importantForAccessibility).toBe('no-hide-descendants');
    unmount(renderer);
  });

  it('keeps loading text accessible beside the decorative mascot', () => {
    const renderer = renderTree(<YuukaLoadingState message="Loading paychecks..." />);

    expect(renderer.root.findByProps({ accessibilityLabel: 'Loading paychecks...' })).toBeTruthy();
    expect(renderer.root.findByProps({ testID: 'yuuka-loading-mascot' })).toBeTruthy();
    expect(renderer.root.findByProps({ children: 'Loading paychecks...' })).toBeTruthy();
    unmount(renderer);
  });
});

function renderTree(element: ReactElement) {
  let renderer: TestRenderer.ReactTestRenderer | undefined;
  act(() => {
    renderer = TestRenderer.create(element);
  });
  if (!renderer) throw new Error('Renderer was not created.');
  return renderer;
}

function mascot(renderer: TestRenderer.ReactTestRenderer) {
  return renderer.root.findByProps({ resizeMode: 'contain' });
}

function unmount(renderer: TestRenderer.ReactTestRenderer) {
  act(() => renderer.unmount());
}
