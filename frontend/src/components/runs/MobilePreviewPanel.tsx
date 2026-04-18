import { QRCodeSVG } from 'qrcode.react';
import { Copy, ExternalLink, Smartphone } from 'lucide-react';
import { useState } from 'react';

/**
 * Mobile preview surface for React Native + Expo projects.
 *
 * v1 scope: the backend ran `expo export` and served the resulting dist/
 * via nginx. The preview URL points to a landing page that explains the
 * bundle + links to the Hermes .hbc asset. This panel wraps that URL with
 * a QR code (so a phone can jump to the page without typing) and with
 * human-readable install / next-step guidance.
 *
 * Full Expo Go QR pairing (Expo updates protocol v2 — manifest served
 * directly, on-device hot reload, Metro dev-server style) is v2 follow-up
 * — see docs/plans/android-codegen-plan.md. This panel is a useful
 * intermediate that surfaces the successfully-bundled artifact without
 * pretending to be something it isn't.
 */
export default function MobilePreviewPanel({ url }: { url: string }) {
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // clipboard blocked (iframe sandbox etc.) — silent fallback
    }
  };

  return (
    <div className="flex-1 overflow-y-auto p-6">
      <div className="max-w-2xl mx-auto space-y-6">
        <div className="flex items-start gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-full bg-accent-emerald/15">
            <Smartphone className="h-5 w-5 text-accent-emerald" />
          </div>
          <div>
            <h2 className="text-base font-semibold text-primary">Mobile preview ready</h2>
            <p className="text-xs text-secondary mt-0.5">
              React Native / Expo bundle compiled successfully. Scan the QR below on your phone
              or open the URL to view the status page.
            </p>
          </div>
        </div>

        <div className="flex flex-col items-center gap-3 p-6 rounded-md border border-[var(--border-default)] bg-[var(--gray-100)]">
          <div className="rounded-md bg-white p-3">
            <QRCodeSVG
              value={url}
              size={192}
              level="M"
              includeMargin={false}
              aria-label="QR code linking to mobile preview URL"
            />
          </div>
          <div className="flex items-center gap-2 text-xs text-secondary font-mono">
            <span className="truncate max-w-[320px]">{url}</span>
            <button
              type="button"
              onClick={copy}
              className="p-1 hover:bg-[var(--gray-200)] rounded"
              aria-label="Copy preview URL"
              title={copied ? 'Copied!' : 'Copy URL'}
            >
              <Copy className="h-3.5 w-3.5" />
            </button>
            <a
              href={url}
              target="_blank"
              rel="noopener noreferrer"
              className="p-1 hover:bg-[var(--gray-200)] rounded"
              aria-label="Open preview URL in new tab"
              title="Open in new tab"
            >
              <ExternalLink className="h-3.5 w-3.5" />
            </a>
          </div>
        </div>

        <div className="space-y-2">
          <h3 className="text-sm font-medium text-primary">Next steps</h3>
          <ol className="text-xs text-secondary space-y-2 list-decimal list-inside">
            <li>
              Install{' '}
              <a
                href="https://expo.dev/client"
                target="_blank"
                rel="noopener noreferrer"
                className="text-accent-emerald hover:underline"
              >
                Expo Go
              </a>{' '}
              on your phone (Play Store or App Store).
            </li>
            <li>
              Full on-device pairing (Expo update protocol, live reload) is a v2 follow-up. Right
              now the URL above opens a status page that links to the compiled Hermes bundle so
              you can confirm the build.
            </li>
            <li>
              To actually run the app on a phone today, clone the workspace and run{' '}
              <code className="px-1 py-0.5 bg-[var(--gray-200)] rounded text-[10px]">
                npx expo start
              </code>{' '}
              locally — then scan Metro's own QR from your terminal.
            </li>
          </ol>
        </div>
      </div>
    </div>
  );
}
