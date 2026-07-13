const semanticReleasePattern = /^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z][0-9A-Za-z.-]*)?$/;
const prefixedSemanticReleasePattern = /^v\d+\.\d+\.\d+(?:[-+][0-9A-Za-z][0-9A-Za-z.-]*)?$/;
const developmentVersion = '0.0.0-dev';

export function formatYuukaVersion(value: string | null | undefined) {
  const version = value?.trim();
  if (!version) return null;
  if (version === developmentVersion) return version;
  if (prefixedSemanticReleasePattern.test(version)) return version;
  if (semanticReleasePattern.test(version)) return `v${version}`;
  return version;
}

export function formatYuukaVersionFooter(value: string | null | undefined) {
  const version = formatYuukaVersion(value);
  return version ? `Yuuka ${version}` : 'Yuuka version unavailable';
}
