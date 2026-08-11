import { StyleSheet, View } from 'react-native';
import Svg, { Circle, Line, Polyline, Rect } from 'react-native-svg';

import type { SpendingBucketInsightPoint } from '@/api/contracts';
import { AppText } from '@/components/app-text';
import { useAppTheme } from '@/theme/use-app-theme';

import { lineChartPoints, netChartGeometry } from './chart-math';

const WIDTH = 320;
const HEIGHT = 150;

export function BudgetSpentChart({ points }: { points: SpendingBucketInsightPoint[] }) {
  const { colors } = useAppTheme();
  const maximum = Math.max(
    ...points.flatMap((point) => [point.budgetedMinor, point.spentMinor]),
    1,
  );
  const budgeted = lineChartPoints(
    points.map((point) => point.budgetedMinor),
    WIDTH,
    HEIGHT,
    12,
    maximum,
  );
  const spent = lineChartPoints(
    points.map((point) => point.spentMinor),
    WIDTH,
    HEIGHT,
    12,
    maximum,
  );
  return (
    <View
      accessibilityLabel="Budgeted versus spent trend graph"
      accessibilityRole="image"
      style={styles.block}
    >
      <View style={styles.legend}>
        <AppText variant="caption">● Budgeted — solid line</AppText>
        <AppText variant="caption">■ Spent - - dashed line</AppText>
      </View>
      <Svg accessible={false} height={HEIGHT} viewBox={`0 0 ${WIDTH} ${HEIGHT}`} width="100%">
        <Polyline fill="none" points={serialize(budgeted)} stroke={colors.accent} strokeWidth={3} />
        <Polyline
          fill="none"
          points={serialize(spent)}
          stroke={colors.processing}
          strokeDasharray="7 5"
          strokeWidth={3}
        />
        {budgeted.map((point, index) => (
          <Circle cx={point.x} cy={point.y} fill={colors.accent} key={`b-${index}`} r={4} />
        ))}
        {spent.map((point, index) => (
          <Rect
            fill={colors.processing}
            height={8}
            key={`s-${index}`}
            width={8}
            x={point.x - 4}
            y={point.y - 4}
          />
        ))}
      </Svg>
    </View>
  );
}

export function NetChart({ points }: { points: SpendingBucketInsightPoint[] }) {
  const { colors } = useAppTheme();
  const geometry = netChartGeometry(
    points.map((point) => point.netMinor),
    WIDTH,
    HEIGHT,
  );
  return (
    <View
      accessibilityLabel="Net under or over graph with zero baseline"
      accessibilityRole="image"
      style={styles.block}
    >
      <View style={styles.legend}>
        <AppText variant="caption">↑ Under budget</AppText>
        <AppText variant="caption">↓ Over budget</AppText>
        <AppText variant="caption">— Zero baseline</AppText>
      </View>
      <Svg accessible={false} height={HEIGHT} viewBox={`0 0 ${WIDTH} ${HEIGHT}`} width="100%">
        <Line
          stroke={colors.text}
          strokeDasharray="3 3"
          strokeWidth={2}
          x1={0}
          x2={WIDTH}
          y1={geometry.baselineY}
          y2={geometry.baselineY}
        />
        {geometry.bars.map((bar, index) => (
          <Rect
            fill={bar.value >= 0 ? colors.postedSoft : colors.dangerSoft}
            height={Math.max(bar.height, bar.value === 0 ? 2 : 0)}
            key={index}
            stroke={bar.value >= 0 ? colors.posted : colors.danger}
            strokeDasharray={bar.value < 0 ? '4 2' : undefined}
            strokeWidth={2}
            width={bar.width}
            x={bar.x}
            y={bar.value === 0 ? geometry.baselineY - 1 : bar.y}
          />
        ))}
      </Svg>
    </View>
  );
}

function serialize(points: { x: number; y: number }[]) {
  return points.map((point) => `${point.x},${point.y}`).join(' ');
}

const styles = StyleSheet.create({
  block: { gap: 8, width: '100%' },
  legend: { flexDirection: 'row', flexWrap: 'wrap', gap: 12 },
});
