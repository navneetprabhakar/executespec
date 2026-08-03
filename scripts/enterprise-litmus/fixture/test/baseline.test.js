import assert from 'node:assert/strict';
import test from 'node:test';
import {catalog, filterCatalog} from '../src/catalog.js';

test('returns a copy when query is empty', () => {
  const result = filterCatalog(catalog);
  assert.deepEqual(result, catalog);
  assert.notEqual(result, catalog);
});

test('filters titles case-insensitively without mutating input', () => {
  const input = catalog.slice(0, 12);
  const before = structuredClone(input);
  assert.deepEqual(filterCatalog(input, {query: 'PRODUCT 007'}).map((item) => item.id), [7]);
  assert.deepEqual(input, before);
});

test('returns an empty list when no title matches', () => {
  assert.deepEqual(filterCatalog(catalog, {query: 'not-in-catalog'}), []);
});
