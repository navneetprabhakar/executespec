import { useState, useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import {
  X, Rocket, Search, ArrowRight, ArrowLeft, CheckCircle2, XCircle,
  AlertTriangle, Loader2, Cloud, Server, Database, Globe, Shield,
  Upload, Eye, ChevronDown, ChevronUp, Plus, Trash2,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { useUiStore } from '@/stores/useUiStore';
import * as api from '@/lib/api';
import type {
  RunSummary, DeployAnalysisResult, RequiredPermission,
  CredentialSummary, ValidationResult,
} from '@/lib/api';

interface Props {
  onClose: () => void;
  onRefresh: () => void;
  /** Pre-select a run if launching from a specific run */
  preselectedRunId?: string;
}

type WizardStep = 'select-run' | 'credentials' | 'analyze' | 'configure' | 'deploying';

const GCP_REGIONS = [
  'us-central1', 'us-east1', 'us-west1', 'europe-west1',
  'europe-west2', 'asia-south1', 'asia-east1', 'asia-northeast1',
];

export default function DeployWizardModal({ onClose, onRefresh, preselectedRunId }: Props) {
  const { addToast } = useUiStore();

  // Wizard state
  const [step, setStep] = useState<WizardStep>(preselectedRunId ? 'credentials' : 'select-run');

  // Step 1: Select run
  const [runs, setRuns] = useState<RunSummary[]>([]);
  const [runsLoading, setRunsLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedRunId, setSelectedRunId] = useState(preselectedRunId || '');

  // Step 2: Credentials
  const [credential, setCredential] = useState<CredentialSummary | null>(null);
  const [credLoading, setCredLoading] = useState(true);
  const [requiredPerms, setRequiredPerms] = useState<RequiredPermission[]>([]);
  const [showPerms, setShowPerms] = useState(false);
  const [validating, setValidating] = useState(false);
  const [validationResult, setValidationResult] = useState<ValidationResult | null>(null);
  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Step 3: Analysis
  const [analyzing, setAnalyzing] = useState(false);
  const [analysis, setAnalysis] = useState<DeployAnalysisResult | null>(null);

  // Step 4: Configure
  const [selectedPath, setSelectedPath] = useState('');
  const [region, setRegion] = useState('us-central1');
  const [envVars, setEnvVars] = useState<Array<{ key: string; value: string }>>([]);

  // Step 5: Deploying
  const [deploying, setDeploying] = useState(false);
  const [deployStatus, setDeployStatus] = useState('');
  const [deployResult, setDeployResult] = useState<api.DeploymentSummary | null>(null);
  const [deployError, setDeployError] = useState('');
  const pollRef = useRef<ReturnType<typeof setInterval>>();

  // ── Load runs (Step 1) ──────────────────────────────────────────────────────

  useEffect(() => {
    if (step !== 'select-run') return;
    setRunsLoading(true);
    api.getRuns({ status: 'COMPLETE' })
      .then(setRuns)
      .catch(() => setRuns([]))
      .finally(() => setRunsLoading(false));
  }, [step]);

  const filteredRuns = runs.filter((r) =>
    r.userRequest.toLowerCase().includes(searchQuery.toLowerCase()) ||
    r.runId.toLowerCase().includes(searchQuery.toLowerCase())
  );

  // ── Load credentials (Step 2) ───────────────────────────────────────────────

  useEffect(() => {
    if (step !== 'credentials') return;
    setCredLoading(true);
    Promise.all([
      api.getGcpCredential().catch(() => null),
      api.getRequiredPermissions().catch(() => []),
    ]).then(([cred, perms]) => {
      setCredential(cred);
      setRequiredPerms(perms);
    }).finally(() => setCredLoading(false));
  }, [step]);

  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setUploading(true);
    try {
      const text = await file.text();
      const cred = await api.storeGcpCredential(text);
      setCredential(cred);
      setValidationResult(null);
      addToast({ type: 'success', message: 'Credentials uploaded successfully' });
    } catch (err: any) {
      addToast({ type: 'error', message: err.message });
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const handleValidate = async () => {
    setValidating(true);
    try {
      const result = await api.validateGcpCredential();
      setValidationResult(result);
      if (result.valid) {
        addToast({ type: 'success', message: 'All required permissions verified' });
      }
    } catch (err: any) {
      addToast({ type: 'error', message: err.message });
    } finally {
      setValidating(false);
    }
  };

  // ── Analyze (Step 3) ────────────────────────────────────────────────────────

  const runAnalysis = async () => {
    setStep('analyze');
    setAnalyzing(true);
    try {
      const result = await api.analyzeForDeploy(selectedRunId);
      setAnalysis(result);
      setSelectedPath(result.decision.recommendedPath);
      // Pre-populate env vars from detected ones
      if (result.manifest.envVars?.length) {
        setEnvVars(result.manifest.envVars.map((key) => ({ key, value: '' })));
      }
    } catch (err: any) {
      addToast({ type: 'error', message: `Analysis failed: ${err.message}` });
      setStep('credentials');
    } finally {
      setAnalyzing(false);
    }
  };

  // ── Deploy (Step 5) ─────────────────────────────────────────────────────────

  const startDeploy = async () => {
    setStep('deploying');
    setDeploying(true);
    setDeployError('');
    setDeployStatus('Initiating deployment...');

    try {
      const envMap: Record<string, string> = {};
      envVars.forEach(({ key, value }) => {
        if (key.trim()) envMap[key.trim()] = value;
      });

      const result = await api.confirmDeploy({
        runId: selectedRunId,
        deploymentPath: selectedPath,
        region,
        envVars: Object.keys(envMap).length > 0 ? envMap : undefined,
        manifestJson: analysis ? JSON.stringify(analysis.manifest) : undefined,
        healthCheckPath: analysis?.manifest.healthCheckPath || undefined,
        appPort: analysis?.manifest.detectedPort || undefined,
      });

      setDeployResult(result);
      setDeployStatus('Build started...');

      // Poll for status updates
      pollRef.current = setInterval(async () => {
        try {
          const detail = await api.getDeploymentDetail(result.id);
          setDeployResult(detail);

          if (detail.status === 'LIVE') {
            setDeployStatus('Deployment is live!');
            setDeploying(false);
            clearInterval(pollRef.current);
            onRefresh();
          } else if (detail.status === 'FAILED' || detail.status === 'DELETED') {
            setDeployStatus(`Deployment ${detail.status.toLowerCase()}`);
            setDeployError(`Deployment ${detail.status.toLowerCase()}. Check logs for details.`);
            setDeploying(false);
            clearInterval(pollRef.current);
          } else if (detail.status === 'BUILDING') {
            setDeployStatus('Building container image...');
          } else if (detail.status === 'PUSHING') {
            setDeployStatus('Pushing image to registry...');
          } else if (detail.status === 'DEPLOYING') {
            setDeployStatus('Deploying to Cloud Run...');
          }
        } catch {
          // Ignore polling errors
        }
      }, 5000);

    } catch (err: any) {
      setDeployError(err.message);
      setDeployStatus('Deployment failed');
      setDeploying(false);
    }
  };

  // Cleanup polling on unmount
  useEffect(() => {
    return () => { if (pollRef.current) clearInterval(pollRef.current); };
  }, []);

  // ── Render ──────────────────────────────────────────────────────────────────

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
      style={{ backdropFilter: 'blur(4px)', background: 'rgba(0,0,0,0.6)' }}
      onClick={onClose}
    >
      <motion.div
        initial={{ opacity: 0, scale: 0.96 }}
        animate={{ opacity: 1, scale: 1 }}
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-2xl max-h-[85vh] rounded-2xl bg-surface-2 border border-white/[0.08] shadow-2xl flex flex-col overflow-hidden"
      >
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/[0.06] flex-shrink-0">
          <div className="flex items-center gap-3">
            <div className="h-8 w-8 rounded-lg bg-accent-violet/20 flex items-center justify-center">
              <Rocket className="h-4 w-4 text-accent-violet" />
            </div>
            <div>
              <h2 className="text-base font-semibold text-white">Deploy to GCP</h2>
              <p className="text-xs text-zinc-500">
                {step === 'select-run' && 'Select a completed run to deploy'}
                {step === 'credentials' && 'Configure GCP credentials'}
                {step === 'analyze' && 'Analyzing workspace...'}
                {step === 'configure' && 'Configure deployment'}
                {step === 'deploying' && 'Deployment in progress'}
              </p>
            </div>
          </div>
          <button onClick={onClose} className="text-zinc-500 hover:text-zinc-300 transition-colors">
            <X className="h-5 w-5" />
          </button>
        </div>

        {/* Step indicator */}
        <div className="flex items-center gap-1 px-6 py-3 border-b border-white/[0.06] flex-shrink-0">
          {(['select-run', 'credentials', 'analyze', 'configure', 'deploying'] as WizardStep[]).map((s, i) => {
            const labels = ['Run', 'GCP Auth', 'Analyze', 'Configure', 'Deploy'];
            const isCurrent = s === step;
            const isPast = ['select-run', 'credentials', 'analyze', 'configure', 'deploying'].indexOf(step) > i;
            return (
              <div key={s} className="flex items-center gap-1">
                {i > 0 && <div className={cn('w-6 h-px', isPast ? 'bg-accent-violet' : 'bg-white/10')} />}
                <div className={cn(
                  'text-[10px] px-2 py-0.5 rounded-full font-medium transition-colors',
                  isCurrent ? 'bg-accent-violet/20 text-accent-violet' :
                  isPast ? 'bg-accent-emerald/10 text-accent-emerald' :
                  'bg-white/[0.04] text-zinc-600'
                )}>
                  {labels[i]}
                </div>
              </div>
            );
          })}
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto p-6">
          <AnimatePresence mode="wait">
            {/* ── Step 1: Select Run ── */}
            {step === 'select-run' && (
              <motion.div key="select-run" initial={{ opacity: 0, x: 20 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -20 }}
                className="space-y-4">
                <div className="relative">
                  <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-zinc-500" />
                  <input
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    placeholder="Search completed runs..."
                    className="w-full pl-9 pr-3 py-2 bg-surface-1 border border-white/[0.08] rounded-lg text-sm text-zinc-200 placeholder:text-zinc-600 focus:outline-none focus:border-accent-violet/40"
                  />
                </div>

                {runsLoading ? (
                  <div className="flex items-center justify-center py-12">
                    <Loader2 className="h-5 w-5 text-zinc-500 animate-spin" />
                  </div>
                ) : filteredRuns.length === 0 ? (
                  <div className="text-center py-12 text-zinc-500 text-sm">
                    No completed runs found
                  </div>
                ) : (
                  <div className="space-y-1.5 max-h-[50vh] overflow-y-auto">
                    {filteredRuns.slice(0, 20).map((run) => (
                      <button
                        key={run.runId}
                        onClick={() => setSelectedRunId(run.runId)}
                        className={cn(
                          'w-full text-left p-3 rounded-lg border transition-all',
                          selectedRunId === run.runId
                            ? 'border-accent-violet/40 bg-accent-violet/5'
                            : 'border-white/[0.06] bg-surface-1 hover:border-white/[0.12]'
                        )}
                      >
                        <p className="text-sm text-zinc-200 line-clamp-2">{run.userRequest}</p>
                        <div className="flex items-center gap-3 mt-1.5">
                          <span className="text-[10px] font-mono text-zinc-600">{run.runId.slice(0, 12)}</span>
                          {run.completedAt && (
                            <span className="text-[10px] text-zinc-600">
                              {new Date(run.completedAt).toLocaleDateString()}
                            </span>
                          )}
                        </div>
                      </button>
                    ))}
                  </div>
                )}
              </motion.div>
            )}

            {/* ── Step 2: Credentials ── */}
            {step === 'credentials' && (
              <motion.div key="credentials" initial={{ opacity: 0, x: 20 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -20 }}
                className="space-y-4">

                {/* Required Permissions (show before upload) */}
                <div className="rounded-lg border border-white/[0.06] bg-surface-1 overflow-hidden">
                  <button
                    onClick={() => setShowPerms(!showPerms)}
                    className="w-full flex items-center justify-between px-4 py-3 hover:bg-white/[0.02] transition-colors"
                  >
                    <div className="flex items-center gap-2">
                      <Shield className="h-4 w-4 text-accent-amber" />
                      <span className="text-sm font-medium text-zinc-300">Required GCP Permissions</span>
                    </div>
                    {showPerms ? <ChevronUp className="h-4 w-4 text-zinc-500" /> : <ChevronDown className="h-4 w-4 text-zinc-500" />}
                  </button>
                  {showPerms && (
                    <div className="px-4 pb-3 space-y-2 border-t border-white/[0.06]">
                      <p className="text-xs text-zinc-500 pt-2 pb-1">
                        Your GCP service account needs these roles to deploy:
                      </p>
                      {requiredPerms.map((perm) => (
                        <div key={perm.role} className="flex items-start gap-2 py-1.5">
                          <div className={cn(
                            'mt-0.5 h-4 w-4 rounded-full flex items-center justify-center flex-shrink-0',
                            perm.required ? 'bg-accent-violet/20' : 'bg-zinc-700/50'
                          )}>
                            <span className="text-[8px] font-bold text-accent-violet">
                              {perm.required ? 'R' : 'O'}
                            </span>
                          </div>
                          <div className="flex-1 min-w-0">
                            <p className="text-xs font-medium text-zinc-300">{perm.label}</p>
                            <p className="text-[10px] text-zinc-500 mt-0.5">{perm.reason}</p>
                            <p className="text-[10px] font-mono text-zinc-600">{perm.role}</p>
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>

                {/* Current credential status */}
                {credLoading ? (
                  <div className="flex items-center justify-center py-8">
                    <Loader2 className="h-5 w-5 text-zinc-500 animate-spin" />
                  </div>
                ) : credential ? (
                  <div className="rounded-lg border border-accent-emerald/20 bg-accent-emerald/5 p-4 space-y-2">
                    <div className="flex items-center gap-2">
                      <CheckCircle2 className="h-4 w-4 text-accent-emerald" />
                      <span className="text-sm font-medium text-accent-emerald">Credentials configured</span>
                    </div>
                    <div className="grid grid-cols-2 gap-2 text-xs">
                      <div>
                        <span className="text-zinc-500">GCP Project:</span>
                        <span className="ml-1 text-zinc-300 font-mono">{credential.gcpProjectId}</span>
                      </div>
                      <div>
                        <span className="text-zinc-500">Service Account:</span>
                        <span className="ml-1 text-zinc-300 font-mono text-[10px]">{credential.serviceAccountEmail}</span>
                      </div>
                    </div>
                    <div className="flex items-center gap-2 pt-1">
                      <button
                        onClick={handleValidate}
                        disabled={validating}
                        className="flex items-center gap-1.5 px-3 py-1.5 rounded-md bg-surface-1 border border-white/[0.08] text-xs text-zinc-300 hover:border-accent-violet/30 transition-colors disabled:opacity-40"
                      >
                        {validating ? <Loader2 className="h-3 w-3 animate-spin" /> : <Eye className="h-3 w-3" />}
                        Validate Permissions
                      </button>
                      <button
                        onClick={() => fileInputRef.current?.click()}
                        disabled={uploading}
                        className="flex items-center gap-1.5 px-3 py-1.5 rounded-md bg-surface-1 border border-white/[0.08] text-xs text-zinc-300 hover:border-accent-amber/30 transition-colors disabled:opacity-40"
                      >
                        <Upload className="h-3 w-3" /> Replace
                      </button>
                    </div>
                  </div>
                ) : (
                  <div className="rounded-lg border border-dashed border-white/[0.12] bg-surface-1 p-6 text-center space-y-3">
                    <Cloud className="h-8 w-8 text-zinc-600 mx-auto" />
                    <div>
                      <p className="text-sm font-medium text-zinc-300">No GCP credentials configured</p>
                      <p className="text-xs text-zinc-500 mt-1">
                        Upload a GCP service account JSON key file to deploy
                      </p>
                    </div>
                    <button
                      onClick={() => fileInputRef.current?.click()}
                      disabled={uploading}
                      className={cn(
                        'inline-flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium',
                        'bg-accent-violet text-white hover:bg-accent-violet/80 transition-colors',
                        'disabled:opacity-40 disabled:cursor-not-allowed'
                      )}
                    >
                      {uploading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Upload className="h-4 w-4" />}
                      Upload Service Account Key
                    </button>
                  </div>
                )}

                {/* Hidden file input */}
                <input
                  ref={fileInputRef}
                  type="file"
                  accept=".json"
                  onChange={handleFileUpload}
                  className="hidden"
                />

                {/* Validation Results */}
                {validationResult && (
                  <div className={cn(
                    'rounded-lg border p-4 space-y-3',
                    validationResult.valid
                      ? 'border-accent-emerald/20 bg-accent-emerald/5'
                      : 'border-accent-amber/20 bg-accent-amber/5'
                  )}>
                    <div className="flex items-center gap-2">
                      {validationResult.valid
                        ? <CheckCircle2 className="h-4 w-4 text-accent-emerald" />
                        : <AlertTriangle className="h-4 w-4 text-accent-amber" />
                      }
                      <span className={cn('text-sm font-medium',
                        validationResult.valid ? 'text-accent-emerald' : 'text-accent-amber'
                      )}>
                        {validationResult.valid ? 'All permissions verified' : 'Some permissions missing'}
                      </span>
                    </div>

                    {validationResult.permissionChecks.length > 0 && (
                      <div className="space-y-1.5">
                        {validationResult.permissionChecks.map((check) => (
                          <div key={check.role} className="flex items-center gap-2 text-xs">
                            {check.granted
                              ? <CheckCircle2 className="h-3.5 w-3.5 text-accent-emerald flex-shrink-0" />
                              : <XCircle className="h-3.5 w-3.5 text-accent-rose flex-shrink-0" />
                            }
                            <span className={cn(
                              'font-medium',
                              check.granted ? 'text-zinc-300' : 'text-accent-rose'
                            )}>
                              {check.label}
                            </span>
                            {!check.required && (
                              <span className="text-[10px] text-zinc-600 bg-white/[0.04] px-1.5 py-0.5 rounded">optional</span>
                            )}
                            <span className="text-zinc-600 ml-auto text-[10px] truncate max-w-[200px]">{check.detail}</span>
                          </div>
                        ))}
                      </div>
                    )}

                    {validationResult.issues.length > 0 && (
                      <div className="space-y-1">
                        {validationResult.issues.map((issue, i) => (
                          <p key={i} className="text-xs text-accent-rose">{issue}</p>
                        ))}
                      </div>
                    )}
                  </div>
                )}
              </motion.div>
            )}

            {/* ── Step 3: Analyze ── */}
            {step === 'analyze' && (
              <motion.div key="analyze" initial={{ opacity: 0, x: 20 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -20 }}>
                {analyzing ? (
                  <div className="flex flex-col items-center justify-center py-16 space-y-4">
                    <div className="relative">
                      <Loader2 className="h-8 w-8 text-accent-violet animate-spin" />
                    </div>
                    <div className="text-center">
                      <p className="text-sm font-medium text-zinc-300">Analyzing workspace</p>
                      <p className="text-xs text-zinc-500 mt-1">
                        Detecting databases, frameworks, ports, and infrastructure requirements...
                      </p>
                    </div>
                  </div>
                ) : analysis ? (
                  <div className="space-y-4">
                    {/* Manifest summary */}
                    <div className="rounded-lg border border-white/[0.06] bg-surface-1 p-4 space-y-3">
                      <h3 className="text-sm font-semibold text-white">Infrastructure Analysis</h3>
                      <div className="grid grid-cols-2 gap-3">
                        <InfoRow icon={<Server className="h-3.5 w-3.5" />} label="Project Type" value={analysis.manifest.projectType} />
                        <InfoRow icon={<Globe className="h-3.5 w-3.5" />} label="Framework" value={analysis.manifest.framework || 'None detected'} />
                        <InfoRow icon={<Database className="h-3.5 w-3.5" />} label="Databases" value={analysis.manifest.databases?.length ? analysis.manifest.databases.join(', ') : 'None'} />
                        <InfoRow icon={<Cloud className="h-3.5 w-3.5" />} label="Port" value={String(analysis.manifest.detectedPort || 8080)} />
                      </div>
                      {analysis.manifest.envVars?.length > 0 && (
                        <div className="pt-2 border-t border-white/[0.06]">
                          <p className="text-xs text-zinc-500 mb-1">Detected environment variables:</p>
                          <div className="flex flex-wrap gap-1">
                            {analysis.manifest.envVars.map((v) => (
                              <span key={v} className="text-[10px] font-mono text-zinc-400 bg-white/[0.04] px-1.5 py-0.5 rounded">{v}</span>
                            ))}
                          </div>
                        </div>
                      )}
                    </div>

                    {/* Recommended path */}
                    <div className="rounded-lg border border-accent-violet/20 bg-accent-violet/5 p-4">
                      <div className="flex items-center gap-2 mb-2">
                        <Rocket className="h-4 w-4 text-accent-violet" />
                        <span className="text-sm font-medium text-accent-violet">Recommended: {analysis.decision.recommendedPath}</span>
                      </div>
                      <p className="text-xs text-zinc-400">{analysis.decision.reasoning}</p>
                    </div>
                  </div>
                ) : null}
              </motion.div>
            )}

            {/* ── Step 4: Configure ── */}
            {step === 'configure' && analysis && (
              <motion.div key="configure" initial={{ opacity: 0, x: 20 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -20 }}
                className="space-y-4">
                {/* Deployment path selection */}
                <div>
                  <label className="block text-sm text-zinc-400 mb-2">Deployment Path</label>
                  <div className="space-y-1.5">
                    {analysis.decision.availablePaths?.map((p) => (
                      <button
                        key={p.path}
                        onClick={() => p.available && setSelectedPath(p.path)}
                        disabled={!p.available}
                        className={cn(
                          'w-full text-left p-3 rounded-lg border transition-all',
                          !p.available && 'opacity-40 cursor-not-allowed',
                          selectedPath === p.path
                            ? 'border-accent-violet/40 bg-accent-violet/5'
                            : 'border-white/[0.06] bg-surface-1 hover:border-white/[0.12]'
                        )}
                      >
                        <div className="flex items-center justify-between">
                          <span className="text-sm font-medium text-zinc-200">{p.label || p.path}</span>
                          {p.path === analysis.decision.recommendedPath && (
                            <span className="text-[10px] px-2 py-0.5 rounded-full bg-accent-violet/20 text-accent-violet font-medium">
                              Recommended
                            </span>
                          )}
                          {!p.available && (
                            <span className="text-[10px] px-2 py-0.5 rounded-full bg-zinc-700/50 text-zinc-500 font-medium">
                              Coming Soon
                            </span>
                          )}
                        </div>
                        <p className="text-xs text-zinc-500 mt-1">{p.description || p.reason}</p>
                      </button>
                    ))}
                  </div>
                </div>

                {/* Region */}
                <div>
                  <label className="block text-sm text-zinc-400 mb-1.5">GCP Region</label>
                  <select
                    value={region}
                    onChange={(e) => setRegion(e.target.value)}
                    className="w-full bg-surface-1 border border-white/[0.08] rounded-lg px-3 py-2 text-sm text-zinc-300 focus:outline-none focus:border-accent-violet/40"
                  >
                    {GCP_REGIONS.map((r) => <option key={r} value={r}>{r}</option>)}
                  </select>
                </div>

                {/* Environment Variables */}
                <div>
                  <div className="flex items-center justify-between mb-1.5">
                    <label className="text-sm text-zinc-400">Environment Variables</label>
                    <button
                      onClick={() => setEnvVars([...envVars, { key: '', value: '' }])}
                      className="flex items-center gap-1 text-xs text-accent-violet hover:text-accent-violet/80"
                    >
                      <Plus className="h-3 w-3" /> Add
                    </button>
                  </div>
                  {envVars.length === 0 ? (
                    <p className="text-xs text-zinc-600 italic">No environment variables configured</p>
                  ) : (
                    <div className="space-y-1.5">
                      {envVars.map((env, i) => (
                        <div key={i} className="flex items-center gap-2">
                          <input
                            value={env.key}
                            onChange={(e) => {
                              const copy = [...envVars];
                              copy[i] = { ...copy[i], key: e.target.value };
                              setEnvVars(copy);
                            }}
                            placeholder="KEY"
                            className="flex-1 bg-surface-1 border border-white/[0.08] rounded-lg px-3 py-1.5 text-xs font-mono text-zinc-300 focus:outline-none focus:border-accent-violet/40"
                          />
                          <input
                            value={env.value}
                            onChange={(e) => {
                              const copy = [...envVars];
                              copy[i] = { ...copy[i], value: e.target.value };
                              setEnvVars(copy);
                            }}
                            placeholder="value"
                            type="password"
                            className="flex-1 bg-surface-1 border border-white/[0.08] rounded-lg px-3 py-1.5 text-xs font-mono text-zinc-300 focus:outline-none focus:border-accent-violet/40"
                          />
                          <button
                            onClick={() => setEnvVars(envVars.filter((_, j) => j !== i))}
                            className="text-zinc-600 hover:text-accent-rose"
                          >
                            <Trash2 className="h-3.5 w-3.5" />
                          </button>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </motion.div>
            )}

            {/* ── Step 5: Deploying ── */}
            {step === 'deploying' && (
              <motion.div key="deploying" initial={{ opacity: 0, x: 20 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -20 }}
                className="flex flex-col items-center justify-center py-12 space-y-6">
                {deploying ? (
                  <>
                    <div className="relative">
                      <div className="h-16 w-16 rounded-full border-2 border-accent-violet/30 flex items-center justify-center">
                        <Loader2 className="h-8 w-8 text-accent-violet animate-spin" />
                      </div>
                    </div>
                    <div className="text-center space-y-1">
                      <p className="text-sm font-medium text-zinc-200">{deployStatus}</p>
                      <p className="text-xs text-zinc-500">This may take a few minutes</p>
                    </div>
                  </>
                ) : deployError ? (
                  <>
                    <div className="h-16 w-16 rounded-full bg-accent-rose/10 flex items-center justify-center">
                      <XCircle className="h-8 w-8 text-accent-rose" />
                    </div>
                    <div className="text-center space-y-1">
                      <p className="text-sm font-medium text-accent-rose">Deployment Failed</p>
                      <p className="text-xs text-zinc-500 max-w-sm">{deployError}</p>
                    </div>
                  </>
                ) : deployResult?.status === 'LIVE' ? (
                  <>
                    <div className="h-16 w-16 rounded-full bg-accent-emerald/10 flex items-center justify-center">
                      <CheckCircle2 className="h-8 w-8 text-accent-emerald" />
                    </div>
                    <div className="text-center space-y-2">
                      <p className="text-sm font-medium text-accent-emerald">Deployment is Live!</p>
                      {deployResult.serviceUrl && (
                        <a
                          href={deployResult.serviceUrl}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="inline-flex items-center gap-1.5 text-xs text-accent-cyan hover:text-accent-cyan/80"
                        >
                          <Globe className="h-3 w-3" /> {deployResult.serviceUrl}
                        </a>
                      )}
                    </div>
                  </>
                ) : null}
              </motion.div>
            )}
          </AnimatePresence>
        </div>

        {/* Footer buttons */}
        <div className="flex items-center justify-between px-6 py-4 border-t border-white/[0.06] flex-shrink-0">
          <div>
            {step !== 'select-run' && step !== 'deploying' && (
              <button
                onClick={() => {
                  if (step === 'credentials') setStep('select-run');
                  else if (step === 'analyze' && !analyzing) setStep('credentials');
                  else if (step === 'configure') setStep('analyze');
                }}
                className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm text-zinc-400 hover:text-zinc-200 transition-colors"
              >
                <ArrowLeft className="h-4 w-4" /> Back
              </button>
            )}
          </div>
          <div className="flex items-center gap-2">
            {step === 'select-run' && (
              <button
                onClick={() => setStep('credentials')}
                disabled={!selectedRunId}
                className={cn(
                  'flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-medium transition-all',
                  'bg-accent-violet text-white hover:bg-accent-violet/80',
                  'disabled:opacity-40 disabled:cursor-not-allowed'
                )}
              >
                Next <ArrowRight className="h-4 w-4" />
              </button>
            )}
            {step === 'credentials' && (
              <button
                onClick={runAnalysis}
                disabled={!credential}
                className={cn(
                  'flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-medium transition-all',
                  'bg-accent-violet text-white hover:bg-accent-violet/80',
                  'disabled:opacity-40 disabled:cursor-not-allowed'
                )}
              >
                Analyze & Continue <ArrowRight className="h-4 w-4" />
              </button>
            )}
            {step === 'analyze' && !analyzing && analysis && (
              <button
                onClick={() => setStep('configure')}
                className="flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-medium bg-accent-violet text-white hover:bg-accent-violet/80 transition-all"
              >
                Configure <ArrowRight className="h-4 w-4" />
              </button>
            )}
            {step === 'configure' && (
              <button
                onClick={startDeploy}
                disabled={!selectedPath}
                className={cn(
                  'flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-medium transition-all',
                  'bg-accent-emerald text-white hover:bg-accent-emerald/80',
                  'disabled:opacity-40 disabled:cursor-not-allowed'
                )}
              >
                <Rocket className="h-4 w-4" /> Deploy
              </button>
            )}
            {step === 'deploying' && !deploying && (
              <button
                onClick={onClose}
                className="flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-medium bg-surface-1 border border-white/[0.08] text-zinc-300 hover:border-white/[0.16] transition-all"
              >
                Close
              </button>
            )}
          </div>
        </div>
      </motion.div>
    </div>
  );
}

function InfoRow({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="flex items-center gap-2">
      <span className="text-zinc-500">{icon}</span>
      <div>
        <p className="text-[10px] text-zinc-600 uppercase tracking-wider">{label}</p>
        <p className="text-xs text-zinc-300">{value}</p>
      </div>
    </div>
  );
}
