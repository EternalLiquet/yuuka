import { useEffect, useState } from 'react';
import { AccessibilityInfo, Image, StyleSheet } from 'react-native';
import type { ImageSourcePropType } from 'react-native';

type YuukaMascotVariant = 'running';

type YuukaMascotProps = {
  playing?: boolean;
  size?: number;
  testID?: string;
  variant?: YuukaMascotVariant;
};

const runningFrames = [
  require('../../assets/yuuka/running/frame-01.png'),
  require('../../assets/yuuka/running/frame-02.png'),
  require('../../assets/yuuka/running/frame-03.png'),
  require('../../assets/yuuka/running/frame-04.png'),
  require('../../assets/yuuka/running/frame-05.png'),
  require('../../assets/yuuka/running/frame-06.png'),
  require('../../assets/yuuka/running/frame-07.png'),
  require('../../assets/yuuka/running/frame-08.png'),
] satisfies ImageSourcePropType[];

const framesByVariant: Record<YuukaMascotVariant, ImageSourcePropType[]> = {
  running: runningFrames,
};

const frameDurationMs = 100;

export function YuukaMascot({
  playing = true,
  size = 74,
  testID,
  variant = 'running',
}: YuukaMascotProps) {
  const reducedMotion = useReducedMotion();
  const [frameIndex, setFrameIndex] = useState(0);
  const frames = framesByVariant[variant];
  const shouldAnimate = playing && !reducedMotion && frames.length > 1;
  const displayedFrameIndex = shouldAnimate ? frameIndex : 0;

  useEffect(() => {
    if (!shouldAnimate) {
      return;
    }

    const interval = setInterval(() => {
      setFrameIndex((current) => (current + 1) % frames.length);
    }, frameDurationMs);

    return () => clearInterval(interval);
  }, [frames.length, shouldAnimate]);

  return (
    <Image
      accessibilityElementsHidden
      accessible={false}
      importantForAccessibility="no-hide-descendants"
      resizeMode="contain"
      source={frames[displayedFrameIndex]}
      style={[styles.image, { height: size, width: size }]}
      testID={testID}
    />
  );
}

function useReducedMotion() {
  const [reducedMotion, setReducedMotion] = useState(false);

  useEffect(() => {
    let mounted = true;
    Promise.resolve(AccessibilityInfo.isReduceMotionEnabled?.() ?? false)
      .then((enabled) => {
        if (mounted) setReducedMotion(enabled);
      })
      .catch(() => undefined);

    const subscription = AccessibilityInfo.addEventListener?.(
      'reduceMotionChanged',
      setReducedMotion,
    );

    return () => {
      mounted = false;
      subscription?.remove?.();
    };
  }, []);

  return reducedMotion;
}

const styles = StyleSheet.create({
  image: {
    flexShrink: 0,
  },
});
