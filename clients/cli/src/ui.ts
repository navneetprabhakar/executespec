import { createInterface } from 'node:readline/promises';
import { stdin, stdout, stderr } from 'node:process';

import { ApiError } from '@executespec/core';

const useColor = Boolean(stdout.isTTY) && !process.env.NO_COLOR;

function c(code: string, s: string): string {
  return useColor ? `\x1b[${code}m${s}\x1b[0m` : s;
}

export const bold = (s: string): string => c('1', s);
export const dim = (s: string): string => c('2', s);
export const red = (s: string): string => c('31', s);
export const green = (s: string): string => c('32', s);
export const yellow = (s: string): string => c('33', s);
export const cyan = (s: string): string => c('36', s);

export function out(line = ''): void {
  stdout.write(line + '\n');
}

export function errln(line: string): void {
  stderr.write(line + '\n');
}

export function printJson(obj: unknown): void {
  stdout.write(JSON.stringify(obj, null, 2) + '\n');
}

export async function prompt(question: string): Promise<string> {
  const rl = createInterface({ input: stdin, output: stdout });
  try {
    return (await rl.question(question)).trim();
  } finally {
    rl.close();
  }
}

export async function confirm(question: string): Promise<boolean> {
  const a = (await prompt(`${question} [y/N] `)).toLowerCase();
  return a === 'y' || a === 'yes';
}

/** Human-readable message for any thrown value (ApiError carries the server's own message). */
export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    return e.status ? `${e.message} (HTTP ${e.status})` : e.message;
  }
  return e instanceof Error ? e.message : String(e);
}
