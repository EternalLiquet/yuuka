(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

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
