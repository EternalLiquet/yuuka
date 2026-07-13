/* eslint-disable @typescript-eslint/no-require-imports */
import type { ReactNode } from 'react';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

require('react-native-gesture-handler/jestSetup');

jest.mock('react-native-safe-area-context', () => {
  const React = require('react');
  const { View } = require('react-native');
  const initialWindowMetrics = {
    frame: { height: 844, width: 390, x: 0, y: 0 },
    insets: { bottom: 0, left: 0, right: 0, top: 0 },
  };

  return {
    SafeAreaProvider: ({ children }: { children: ReactNode }) =>
      React.createElement(View, null, children),
    SafeAreaView: ({ children, ...props }: { children: ReactNode; [key: string]: unknown }) =>
      React.createElement(View, props, children),
    initialWindowMetrics,
    useSafeAreaFrame: () => initialWindowMetrics.frame,
    useSafeAreaInsets: () => initialWindowMetrics.insets,
  };
});

jest.mock('react-native-reanimated', () => {
  const React = require('react');
  const ReactNative = require('react-native');

  const identity = (value: unknown) => value;
  const Animated = {
    ...ReactNative.Animated,
    FlatList: ReactNative.FlatList,
    ScrollView: ReactNative.ScrollView,
    Text: ReactNative.Text,
    View: ReactNative.View,
    call: jest.fn(),
    createAnimatedComponent: (component: unknown) => component,
  };

  return {
    ...Animated,
    Easing: {
      linear: identity,
      out: identity,
    },
    Extrapolation: {
      CLAMP: 'clamp',
      EXTEND: 'extend',
      IDENTITY: 'identity',
    },
    cancelAnimation: jest.fn(),
    default: Animated,
    interpolate: jest.fn((value: unknown) => value),
    interpolateColor: jest.fn((value: unknown) => value),
    makeMutable: (value: unknown) => ({ value }),
    measure: jest.fn(),
    runOnJS: (callback: unknown) => callback,
    runOnUI: (callback: unknown) => callback,
    scrollTo: jest.fn(),
    useAnimatedGestureHandler: jest.fn(() => ({})),
    useAnimatedProps: (callback: () => unknown) => callback(),
    useAnimatedReaction: jest.fn(),
    useAnimatedRef: () => React.createRef(),
    useAnimatedScrollHandler: (handler: unknown) => handler,
    useAnimatedStyle: (callback: () => unknown) => callback(),
    useDerivedValue: (callback: () => unknown) => ({ value: callback() }),
    useSharedValue: (value: unknown) => ({ value }),
    withDecay: identity,
    withDelay: (_delay: number, value: unknown) => value,
    withSpring: identity,
    withTiming: (value: unknown, _config?: unknown, callback?: (finished: boolean) => void) => {
      callback?.(true);
      return value;
    },
  };
});

jest.mock('lucide-react-native', () => {
  const React = require('react');
  const { View } = require('react-native');
  const MockIcon = (props: Record<string, unknown>) => React.createElement(View, props);
  return new Proxy(
    { __esModule: true },
    {
      get(target, property) {
        if (property in target) return target[property as keyof typeof target];
        return MockIcon;
      },
    },
  );
});

// Cold mascot loads intentionally remain visible for 1,000 ms. Give route queries enough room to
// observe the post-load UI; exact timing stays covered by the dedicated fake-timer tests.
require('@testing-library/react-native').configure({ asyncUtilTimeout: 2500 });
