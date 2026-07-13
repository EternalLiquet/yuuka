import { useEffect, useRef, useState } from 'react';
import { AccessibilityInfo, Image, StyleSheet } from 'react-native';
import type { ImageSourcePropType } from 'react-native';

export type YuukaMascotVariant = 'clipboard' | 'heart' | 'idle' | 'running' | 'wave';
export type YuukaMascotPlayback = 'loop' | 'once' | 'static';

type YuukaMascotProps = {
  onPlaybackEnd?: () => void;
  playback?: YuukaMascotPlayback;
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

const idleFrame = require('../../assets/yuuka/idle/frame-01.png') as ImageSourcePropType;
const heartFrame = require('../../assets/yuuka/heart/frame-01.png') as ImageSourcePropType;

const framesByVariant: Record<YuukaMascotVariant, ImageSourcePropType[]> = {
  clipboard: [require('../../assets/yuuka/clipboard/frame-01.png')],
  heart: [idleFrame, heartFrame],
  idle: [idleFrame],
  running: runningFrames,
  wave: [require('../../assets/yuuka/wave/frame-01.png')],
};

const frameDurationMs = 100;

export function YuukaMascot({
  onPlaybackEnd,
  playback = 'loop',
  size = 74,
  testID,
  variant = 'running',
}: YuukaMascotProps) {
  return (
    <YuukaMascotAnimation
      key={`${variant}:${playback}`}
      onPlaybackEnd={onPlaybackEnd}
      playback={playback}
      size={size}
      testID={testID}
      variant={variant}
    />
  );
}

function YuukaMascotAnimation({
  onPlaybackEnd,
  playback,
  size,
  testID,
  variant,
}: Required<Pick<YuukaMascotProps, 'playback' | 'size' | 'variant'>> &
  Pick<YuukaMascotProps, 'onPlaybackEnd' | 'testID'>) {
  const reducedMotion = useReducedMotion();
  const [frameIndex, setFrameIndex] = useState(0);
  const onPlaybackEndRef = useRef(onPlaybackEnd);
  const frames = framesByVariant[variant];
  const shouldAnimate = playback !== 'static' && !reducedMotion && frames.length > 1;
  const displayedFrameIndex = reducedMotion && playback === 'once' ? frames.length - 1 : frameIndex;

  useEffect(() => {
    onPlaybackEndRef.current = onPlaybackEnd;
  }, [onPlaybackEnd]);

  useEffect(() => {
    if (!shouldAnimate) {
      return;
    }

    const interval = setInterval(() => {
      setFrameIndex((current) => {
        if (playback === 'once' && current >= frames.length - 1) {
          clearInterval(interval);
          onPlaybackEndRef.current?.();
          return current;
        }
        return (current + 1) % frames.length;
      });
    }, frameDurationMs);

    return () => clearInterval(interval);
  }, [frames.length, playback, shouldAnimate]);

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
