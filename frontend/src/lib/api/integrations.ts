import {
  AuthUser,
  GitHubAppInstallUrl,
  GitHubAppStatus,
  RunSummary,
  SlackChannel,
  SlackStatus,
  ValidationResult,
  req,
} from './core';

export const getIntegrationStatus = () => req<{enabledSources: string[]}>('/integrations/status');

export const getIntegrationRuns = (source?: string) =>
  req<RunSummary[]>(`/integrations/runs${source ? `?source=${source}` : ''}`);

export const triggerConfluence = (body: {pageId: string; goal: string}) =>
  req<{runId: string}>('/integrations/confluence/trigger', {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const triggerJira = (body: {issueKey: string; goal?: string}) =>
  req<{runId: string}>('/integrations/jira/trigger', {method: 'POST', body: JSON.stringify(body)});

export const getSlackStatus = () => req<SlackStatus>('/integrations/slack/status');

export const getSlackInstallUrl = () => req<{installUrl: string}>('/integrations/slack/install');

export const disconnectSlack = () =>
  req<void>('/integrations/slack/disconnect', {method: 'DELETE'});

export const listSlackChannels = () => req<SlackChannel[]>('/integrations/slack/channels');

export const updateSlackChannelVerbosity = (
  projectId: string,
  channelId: string,
  verbosity: string,
) =>
  req<void>(`/integrations/slack/projects/${projectId}/channels/${channelId}/verbosity`, {
    method: 'PUT',
    body: JSON.stringify({verbosity}),
  });

export const sendSlackTestMessage = (channelId: string) =>
  req<{ok: boolean; message: string}>('/integrations/slack/test', {
    method: 'POST',
    body: JSON.stringify({channelId}),
  });

export const pushToGitHub = (runId: string, repoMode = 'NEW') =>
  req<{status: string; repoUrl: string}>(`/runs/${runId}/push-github?repoMode=${repoMode}`, {
    method: 'POST',
  });

/** SCM-agnostic push — routes to the user's configured SCM provider (GitHub or Bitbucket). */
export const pushToScm = (runId: string) =>
  req<{status: string; repoUrl: string}>(`/runs/${runId}/push-scm`, {method: 'POST'});

/**
 * Saves the user's GitHub PAT and owner (username or org).
 * Pass empty strings to clear the credentials.
 */
export const updateGithubSettings = (githubPat: string, githubOwner: string) =>
  req<AuthUser>('/auth/settings/github', {
    method: 'PUT',
    body: JSON.stringify({githubPat, githubOwner}),
  });

/** Saves per-user JIRA credentials. Pass empty strings to clear. */
export const updateJiraSettings = (jiraBaseUrl: string, jiraEmail: string, jiraApiToken: string) =>
  req<AuthUser>('/auth/settings/jira', {
    method: 'PUT',
    body: JSON.stringify({jiraBaseUrl, jiraEmail, jiraApiToken}),
  });

/** Saves per-user Confluence credentials. Pass empty strings to clear. */
export const updateConfluenceSettings = (
  confluenceBaseUrl: string,
  confluenceEmail: string,
  confluenceApiToken: string,
) =>
  req<AuthUser>('/auth/settings/confluence', {
    method: 'PUT',
    body: JSON.stringify({confluenceBaseUrl, confluenceEmail, confluenceApiToken}),
  });

/** Saves per-user Bitbucket credentials. Pass empty strings to clear. */
export const updateBitbucketSettings = (
  username: string,
  appPassword: string,
  workspace: string,
  repoSlug: string,
) =>
  req<AuthUser>('/auth/settings/bitbucket', {
    method: 'PUT',
    body: JSON.stringify({username, appPassword, workspace, repoSlug}),
  });

/** Tests Bitbucket credentials by calling the Bitbucket API. */
export const validateBitbucket = (
  username: string,
  appPassword: string,
  workspace: string,
  repoSlug: string,
) =>
  req<ValidationResult>('/auth/settings/bitbucket/validate', {
    method: 'POST',
    body: JSON.stringify({username, appPassword, workspace, repoSlug}),
  });

/** Tests a GitHub PAT by calling the GitHub API. */
export const validateGitHub = (pat: string) =>
  req<ValidationResult>('/auth/settings/github/validate', {
    method: 'POST',
    body: JSON.stringify({pat}),
  });
export const getGitHubAppStatus = () => req<GitHubAppStatus>('/auth/github-app/status');
export const getGitHubAppInstallUrl = () =>
  req<GitHubAppInstallUrl>('/auth/github-app/install-url');
export const uninstallGitHubApp = () => req<void>('/auth/github-app/install', {method: 'DELETE'});

/** Tests JIRA credentials by calling the JIRA REST API. */
export const validateJira = (baseUrl: string, email: string, apiToken: string) =>
  req<ValidationResult>('/auth/settings/jira/validate', {
    method: 'POST',
    body: JSON.stringify({baseUrl, email, apiToken}),
  });

/** Tests Confluence credentials by calling the Confluence REST API. */
export const validateConfluence = (baseUrl: string, email: string, apiToken: string) =>
  req<ValidationResult>('/auth/settings/confluence/validate', {
    method: 'POST',
    body: JSON.stringify({baseUrl, email, apiToken}),
  });
