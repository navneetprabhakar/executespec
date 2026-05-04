import {useEffect, useState} from 'react';
import {BookOpen, Download, FileText, RefreshCw, Sparkles} from 'lucide-react';
import Button from '@/components/shared/Button';
import {MarkdownContent} from '@/components/shared/MarkdownContent';
import {
  generateProjectDoc,
  getProjectDoc,
  downloadProjectDoc,
  type GeneratedDocRow,
  type GeneratedDocType,
} from '@/lib/api';
import {useUiStore} from '@/stores/useUiStore';
import {relativeTime, errMsg} from '@/lib/utils';

interface Props {
  projectId: string;
}

type DocState = {
  loading: boolean;
  generating: boolean;
  doc: GeneratedDocRow | null;
  error: string | null;
};

const initial: DocState = {loading: true, generating: false, doc: null, error: null};

/**
 * v2.5.12 — project-level generated docs panel. Lets the user generate two
 * documents from the linked-repo architecture: an EXPLANATION (onboarding
 * narrative) and a SETUP (local-dev README). Both are downloadable as .md.
 *
 * Shows the latest generated version if one exists; otherwise surfaces a
 * Generate CTA. A Regenerate button bumps the version.
 */
export default function GeneratedDocsPanel({projectId}: Props) {
  const [explanation, setExplanation] = useState<DocState>(initial);
  const [setup, setSetup] = useState<DocState>(initial);
  const [expanded, setExpanded] = useState<GeneratedDocType | null>(null);
  const {addToast} = useUiStore();

  useEffect(() => {
    loadDoc('EXPLANATION');
    loadDoc('SETUP');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  const loadDoc = async (docType: GeneratedDocType) => {
    const setter = docType === 'EXPLANATION' ? setExplanation : setSetup;
    setter((s) => ({...s, loading: true, error: null}));
    try {
      const row = await getProjectDoc(projectId, docType);
      setter({loading: false, generating: false, doc: row, error: null});
    } catch (e) {
      setter({loading: false, generating: false, doc: null, error: errMsg(e) ?? String(e)});
    }
  };

  const generate = async (docType: GeneratedDocType) => {
    const setter = docType === 'EXPLANATION' ? setExplanation : setSetup;
    setter((s) => ({...s, generating: true, error: null}));
    try {
      const row = await generateProjectDoc(projectId, docType);
      setter({loading: false, generating: false, doc: row, error: null});
      addToast({
        type: 'success',
        message: `${docType === 'EXPLANATION' ? 'Explanation' : 'Setup'} doc generated (v${row.version})`,
      });
      setExpanded(docType);
    } catch (e) {
      const msg = errMsg(e) ?? String(e);
      setter((s) => ({...s, generating: false, error: msg}));
      addToast({type: 'error', message: msg});
    }
  };

  const download = async (docType: GeneratedDocType) => {
    try {
      await downloadProjectDoc(projectId, docType);
    } catch (e) {
      addToast({type: 'error', message: errMsg(e) ?? String(e)});
    }
  };

  return (
    <section className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-4 space-y-3'>
      <header className='flex items-center justify-between'>
        <h3 className='text-[11px] font-mono font-medium uppercase tracking-wider text-secondary'>
          Generated Docs
        </h3>
        <span className='text-[10px] text-muted'>Project-level onboarding + setup</span>
      </header>

      <DocRow
        icon={<BookOpen className='h-3.5 w-3.5 text-accent-blue' />}
        label='Explanation'
        sub='Narrative onboarding doc for new teammates.'
        state={explanation}
        expanded={expanded === 'EXPLANATION'}
        onGenerate={() => generate('EXPLANATION')}
        onDownload={() => download('EXPLANATION')}
        onToggleExpand={() => setExpanded(expanded === 'EXPLANATION' ? null : 'EXPLANATION')}
      />

      <DocRow
        icon={<FileText className='h-3.5 w-3.5 text-accent-green' />}
        label='Setup'
        sub='Local-dev README: clone, env vars, first-run commands.'
        state={setup}
        expanded={expanded === 'SETUP'}
        onGenerate={() => generate('SETUP')}
        onDownload={() => download('SETUP')}
        onToggleExpand={() => setExpanded(expanded === 'SETUP' ? null : 'SETUP')}
      />

      <p className='text-[10px] text-muted leading-relaxed'>
        Both docs are derived from the latest architecture + codemaps. Run
        <span className='mx-1 text-secondary'>Analyse</span> first to refresh source material, then
        generate.
      </p>
    </section>
  );
}

function DocRow({
  icon,
  label,
  sub,
  state,
  expanded,
  onGenerate,
  onDownload,
  onToggleExpand,
}: {
  icon: React.ReactNode;
  label: string;
  sub: string;
  state: DocState;
  expanded: boolean;
  onGenerate: () => void;
  onDownload: () => void;
  onToggleExpand: () => void;
}) {
  const {loading, generating, doc, error} = state;

  return (
    <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] p-3 space-y-2'>
      <div className='flex items-start justify-between gap-3'>
        <div className='min-w-0'>
          <div className='flex items-center gap-2 text-sm font-medium text-primary'>
            {icon}
            {label}
            {doc && <span className='text-[10px] font-mono text-muted'>v{doc.version}</span>}
          </div>
          <p className='text-[11px] text-muted mt-0.5'>{sub}</p>
          {doc && (
            <p className='text-[10px] text-muted mt-1'>
              Generated {relativeTime(doc.generatedAt)}
              {doc.modelUsed && <span className='ml-1'>· model: {doc.modelUsed}</span>}
            </p>
          )}
          {error && <p className='text-[11px] text-accent-red mt-1'>{error}</p>}
        </div>

        <div className='flex items-center gap-1.5 shrink-0'>
          {doc ? (
            <>
              <Button variant='ghost' size='sm' onClick={onToggleExpand}>
                {expanded ? 'Collapse' : 'View'}
              </Button>
              <Button variant='tertiary' size='sm' onClick={onDownload}>
                <Download className='h-3 w-3' />
                .md
              </Button>
              <Button variant='secondary' size='sm' loading={generating} onClick={onGenerate}>
                <RefreshCw className='h-3 w-3' />
                Regenerate
              </Button>
            </>
          ) : (
            <Button
              variant='primary'
              size='sm'
              loading={generating || loading}
              disabled={loading}
              onClick={onGenerate}
            >
              <Sparkles className='h-3 w-3' />
              {loading ? 'Checking…' : 'Generate'}
            </Button>
          )}
        </div>
      </div>

      {expanded && doc && (
        <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 max-h-[400px] overflow-y-auto'>
          <MarkdownContent content={doc.content} />
        </div>
      )}
    </div>
  );
}
