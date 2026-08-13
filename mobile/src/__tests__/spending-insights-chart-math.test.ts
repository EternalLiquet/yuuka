import { lineChartPoints, netChartGeometry } from '@/features/spending-insights/chart-math';

describe('Spending Insights chart math', () => {
  it('centers one point and scales multiple nonnegative values', () => {
    expect(lineChartPoints([500], 320, 150)).toEqual([{ x: 160, y: 12 }]);
    const points = lineChartPoints([0, 1000], 320, 150);
    expect(points[0]).toEqual({ x: 12, y: 138 });
    expect(points[1]).toEqual({ x: 308, y: 12 });
    expect(lineChartPoints([1000], 320, 150, 12, 2000)).toEqual([{ x: 160, y: 75 }]);
  });

  it('places positive, negative, and zero bars around a visible baseline', () => {
    const geometry = netChartGeometry([1000, -500, 0], 320, 150);
    expect(geometry.baselineY).toBe(75);
    expect(geometry.bars[0].y).toBe(12);
    expect(geometry.bars[1].y).toBe(75);
    expect(geometry.bars[2].height).toBe(0);
  });
});
