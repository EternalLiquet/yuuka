import type { SpendingBucketInsightPoint } from '@/api/contracts';

export type ChartPoint = { x: number; y: number };

export function lineChartPoints(
  values: number[],
  width: number,
  height: number,
  padding = 12,
  maximum?: number,
): ChartPoint[] {
  if (!values.length) return [];
  const max = Math.max(maximum ?? Math.max(...values), 1);
  const xStep = values.length === 1 ? 0 : (width - padding * 2) / (values.length - 1);
  return values.map((value, index) => ({
    x: values.length === 1 ? width / 2 : padding + index * xStep,
    y: height - padding - (value / max) * (height - padding * 2),
  }));
}

export function netChartGeometry(values: number[], width: number, height: number, padding = 12) {
  const half = height / 2;
  const maximum = Math.max(...values.map(Math.abs), 1);
  const slot = values.length ? (width - padding * 2) / values.length : 0;
  const barWidth = Math.min(20, Math.max(5, slot * 0.56));
  return {
    baselineY: half,
    bars: values.map((value, index) => {
      const magnitude = (Math.abs(value) / maximum) * (half - padding);
      return {
        height: magnitude,
        value,
        width: barWidth,
        x: padding + index * slot + (slot - barWidth) / 2,
        y: value >= 0 ? half - magnitude : half,
      };
    }),
  };
}

export function insightNetLabel(point: SpendingBucketInsightPoint) {
  if (point.netMinor > 0) return 'Under';
  if (point.netMinor < 0) return 'Over';
  return 'Exactly on budget';
}
