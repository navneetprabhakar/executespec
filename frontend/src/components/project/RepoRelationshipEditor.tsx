import { useState } from 'react';

interface Props {
  initial: string[];
  onSave: (next: string[]) => void;
  onCancel: () => void;
}

export default function RepoRelationshipEditor({ initial, onSave, onCancel }: Props) {
  const [items, setItems] = useState<string[]>(initial);
  const [draft, setDraft] = useState('');

  const add = () => {
    const trimmed = draft.trim();
    if (!trimmed) return;
    setItems([...items, trimmed]);
    setDraft('');
  };

  const remove = (idx: number) => setItems(items.filter((_, i) => i !== idx));

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-2">
        {items.map((item, idx) => (
          <span
            key={`${item}-${idx}`}
            className="inline-flex items-center gap-1 rounded bg-gray-100 px-2 py-0.5 text-xs"
          >
            {item}
            <button onClick={() => remove(idx)} className="text-muted hover:text-status-error">
              ×
            </button>
          </span>
        ))}
      </div>
      <div className="flex gap-2">
        <input
          type="text"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              add();
            }
          }}
          placeholder="e.g. calls api-server"
          className="flex-1 px-2 py-1 text-xs border rounded"
        />
        <button
          onClick={add}
          className="text-xs px-2 py-1 border rounded hover:bg-gray-50"
        >
          Add
        </button>
        <button
          onClick={() => onSave(items)}
          className="text-xs px-2 py-1 bg-blue-600 text-white rounded hover:bg-blue-700"
        >
          Save
        </button>
        <button
          onClick={onCancel}
          className="text-xs px-2 py-1 border rounded hover:bg-gray-50"
        >
          Cancel
        </button>
      </div>
    </div>
  );
}
