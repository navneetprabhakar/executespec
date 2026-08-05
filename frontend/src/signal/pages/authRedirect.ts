/** Returns a strict same-origin application path, preserving its query and hash. */
function containsControlCharacter(value: string): boolean {
  return [...value].some((character) => {
    const code = character.charCodeAt(0);
    return code < 0x20 || code === 0x7f;
  });
}

export function safeInternalRedirect(value: string | null | undefined): string | undefined {
  if (
    !value ||
    value.length > 2048 ||
    !value.startsWith('/') ||
    value.startsWith('//') ||
    value.includes('\\') ||
    containsControlCharacter(value)
  ) {
    return undefined;
  }
  try {
    const base = new URL('https://executespec.invalid');
    const resolved = new URL(value, base);
    if (resolved.origin !== base.origin) return undefined;
    const decodedPath = decodeURIComponent(resolved.pathname);
    return decodedPath.startsWith('//') ||
      decodedPath.includes('\\') ||
      containsControlCharacter(decodedPath)
      ? undefined
      : value;
  } catch {
    return undefined;
  }
}
