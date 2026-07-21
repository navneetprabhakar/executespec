import {useEffect, useState} from 'react';

/**
 * Which OAuth providers this deployment can actually complete a login with.
 *
 * The backend only registers the OAuth2 filter chain when a client id is configured, so on an
 * environment without one, /api/auth/oauth2/authorize/{provider} does not exist. Rendering the
 * button anyway navigated the browser out of the SPA onto a raw JSON 404. Ask first, render second.
 */
export type AuthProviders = {
  oauthProviders: string[];
  passwordEnabled: boolean;
};

/** Conservative default: assume no OAuth until the server says otherwise. */
const NONE: AuthProviders = {oauthProviders: [], passwordEnabled: true};

export async function fetchAuthProviders(signal?: AbortSignal): Promise<AuthProviders> {
  try {
    const res = await fetch('/api/auth/providers', {signal});
    if (!res.ok) return NONE;
    const body = (await res.json()) as Partial<AuthProviders>;
    return {
      oauthProviders: Array.isArray(body.oauthProviders) ? body.oauthProviders : [],
      passwordEnabled: body.passwordEnabled !== false,
    };
  } catch {
    // Network failure, or an older server without the endpoint. Falling back to "no OAuth" keeps a
    // dead button off the page; email/password still works, so the user is never locked out.
    return NONE;
  }
}

/**
 * Hook form for the auth surfaces. Starts empty so a slow response can never flash a button that
 * would 404 if clicked.
 */
export function useAuthProviders(): AuthProviders {
  const [providers, setProviders] = useState<AuthProviders>(NONE);
  useEffect(() => {
    const ac = new AbortController();
    void fetchAuthProviders(ac.signal).then(setProviders);
    return () => ac.abort();
  }, []);
  return providers;
}
