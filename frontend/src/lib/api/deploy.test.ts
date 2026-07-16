import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {analyzeForDeploy, confirmDeploy, getDeployments} from './deploy';

describe('deployment API contract', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.stubGlobal('localStorage', {
      getItem: () => 'test-token',
      setItem: vi.fn(),
      removeItem: vi.fn(),
    });
  });

  afterEach(() => vi.unstubAllGlobals());

  it('keeps deployment collection requests on the deployment endpoint', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(JSON.stringify([]), {status: 200}));
    vi.stubGlobal('fetch', fetchMock);

    await expect(getDeployments()).resolves.toEqual([]);
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/deployments',
      expect.objectContaining({
        headers: expect.objectContaining({Authorization: 'Bearer test-token'}),
      }),
    );
  });

  it('encodes the run id for deployment analysis', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(JSON.stringify({hasGcpCredentials: false}), {status: 200}));
    vi.stubGlobal('fetch', fetchMock);

    await analyzeForDeploy('run/with spaces');
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/deployments/analyze?runId=run%2Fwith%20spaces');
  });

  it('preserves the confirm deployment payload contract', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(JSON.stringify({id: 'deployment-1'}), {status: 200}));
    vi.stubGlobal('fetch', fetchMock);
    const body = {runId: 'run-1', deploymentPath: 'CLOUD_RUN', region: 'us-east4'};

    await confirmDeploy(body);
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/deployments/confirm');
    expect(fetchMock.mock.calls[0]?.[1]).toMatchObject({
      method: 'POST',
      body: JSON.stringify(body),
    });
  });
});
