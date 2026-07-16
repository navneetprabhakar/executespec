import {describe, expect, it} from 'vitest';

import {diffHunks, hunkRevertRequest, type DiffLine} from './IdeKit';

describe('diffHunks', () => {
  it('groups nearby edits and keeps context around each hunk', () => {
    const diff: DiffLine[] = [
      {sign: ' ', text: 'zero'},
      {sign: ' ', text: 'one'},
      {sign: '+', text: 'two'},
      {sign: ' ', text: 'three'},
      {sign: ' ', text: 'four'},
      {sign: ' ', text: 'five'},
      {sign: ' ', text: 'six'},
      {sign: '-', text: 'seven'},
      {sign: ' ', text: 'eight'},
      {sign: ' ', text: 'nine'},
    ];

    expect(diffHunks(diff, 1)).toEqual([
      {start: 1, end: 3},
      {start: 6, end: 8},
    ]);
  });

  it('merges edits whose context windows touch and ignores unchanged diffs', () => {
    expect(
      diffHunks([
        {sign: '+', text: 'a'},
        {sign: ' ', text: 'b'},
        {sign: '-', text: 'c'},
      ]),
    ).toEqual([{start: 0, end: 2}]);
    expect(diffHunks([{sign: ' ', text: 'same'}])).toEqual([]);
  });

  it('builds an exact, bounded revert request from the selected hunk', () => {
    const diff: DiffLine[] = [
      {sign: ' ', text: 'before'},
      {sign: '-', text: 'old'},
      {sign: '+', text: 'new'},
      {sign: ' ', text: 'after'},
    ];
    const request = hunkRevertRequest('src/example.ts', diff, 0);
    expect(request).toContain('Revert ONLY hunk 1 in src/example.ts.');
    expect(request).toContain('old');
    expect(request).toContain('new');
    expect(request).toContain(
      'Do not change any other hunk, file, dependency, formatting, or tests.',
    );
    expect(hunkRevertRequest('src/example.ts', diff, 1)).toBe('');
  });
});
