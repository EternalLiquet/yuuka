import TestRenderer, { act } from 'react-test-renderer';
import type { ReactElement } from 'react';
import { AccessibilityInfo, Pressable } from 'react-native';

import {
  EmptyState,
  ErrorState,
  YuukaLoadingState,
  YuukaRefreshIndicator,
} from '@/components/states';
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
    const renderer = renderTree(<YuukaMascot playback="static" testID="mascot" />);
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

  it('starts and stops animation when playback changes', () => {
    const renderer = renderTree(<YuukaMascot playback="static" testID="mascot" />);
    const firstSource = mascot(renderer).props.source;

    act(() => renderer.update(<YuukaMascot playback="loop" testID="mascot" />));
    act(() => jest.advanceTimersByTime(100));
    expect(mascot(renderer).props.source).not.toBe(firstSource);

    act(() => renderer.update(<YuukaMascot playback="static" testID="mascot" />));
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

  it('plays a one-shot sequence once and holds its final frame', () => {
    const onPlaybackEnd = jest.fn();
    const renderer = renderTree(
      <YuukaMascot onPlaybackEnd={onPlaybackEnd} playback="once" testID="mascot" variant="heart" />,
    );
    const firstSource = mascot(renderer).props.source;

    act(() => jest.advanceTimersByTime(200));

    expect(mascot(renderer).props.source).not.toBe(firstSource);
    expect(onPlaybackEnd).toHaveBeenCalledTimes(1);
    act(() => jest.advanceTimersByTime(500));
    expect(onPlaybackEnd).toHaveBeenCalledTimes(1);
    unmount(renderer);
  });

  it('uses the static heart pose for one-shot playback under reduced motion', () => {
    const renderer = renderTree(<YuukaMascot playback="once" testID="mascot" variant="heart" />);
    const animatedInitialSource = mascot(renderer).props.source;

    act(() => reduceMotionHandler(true));

    expect(mascot(renderer).props.source).not.toBe(animatedInitialSource);
    act(() => jest.advanceTimersByTime(500));
    expect(mascot(renderer).props.source).not.toBe(animatedInitialSource);
    unmount(renderer);
  });

  it('keeps loading text accessible beside the decorative mascot', () => {
    const renderer = renderTree(<YuukaLoadingState message="Loading paychecks..." />);

    expect(renderer.root.findByProps({ accessibilityLabel: 'Loading paychecks...' })).toBeTruthy();
    expect(renderer.root.findByProps({ testID: 'yuuka-loading-mascot' })).toBeTruthy();
    expect(renderer.root.findByProps({ children: 'Loading paychecks...' })).toBeTruthy();
    unmount(renderer);
  });

  it('renders requested empty-state art decoratively while preserving copy', () => {
    const onAction = jest.fn();
    const renderer = renderTree(
      <EmptyState
        action={<Pressable accessibilityLabel="Create one" onPress={onAction} />}
        mascot="clipboard"
        message="Nothing matched."
        title="No results"
      />,
    );

    expect(renderer.root.findByProps({ testID: 'empty-state-mascot-clipboard' })).toBeTruthy();
    expect(renderer.root.findByProps({ children: 'No results' })).toBeTruthy();
    expect(renderer.root.findByProps({ children: 'Nothing matched.' })).toBeTruthy();
    expect(mascot(renderer).props.accessible).toBe(false);
    act(() => renderer.root.findByProps({ accessibilityLabel: 'Create one' }).props.onPress());
    expect(onAction).toHaveBeenCalledTimes(1);
    unmount(renderer);
  });

  it('keeps error states mascot-free', () => {
    const renderer = renderTree(<ErrorState message="Offline" retry={jest.fn()} />);

    expect(renderer.root.findAllByType(YuukaMascot)).toHaveLength(0);
    unmount(renderer);
  });

  it('keeps a short refresh readable without blocking controls', () => {
    const renderer = renderTree(<YuukaRefreshIndicator visible />);

    expect(
      renderer.root.findByProps({ testID: 'yuuka-refresh-indicator' }).props.pointerEvents,
    ).toBe('none');
    act(() => jest.advanceTimersByTime(100));
    act(() => renderer.update(<YuukaRefreshIndicator visible={false} />));
    expect(renderer.root.findByProps({ testID: 'yuuka-refresh-indicator' })).toBeTruthy();

    act(() => jest.advanceTimersByTime(699));
    expect(renderer.root.findByProps({ testID: 'yuuka-refresh-indicator' })).toBeTruthy();
    act(() => jest.advanceTimersByTime(1));
    expect(renderer.root.findAllByProps({ testID: 'yuuka-refresh-indicator' })).toHaveLength(0);
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
