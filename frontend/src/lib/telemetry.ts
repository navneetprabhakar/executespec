import {WebTracerProvider, BatchSpanProcessor} from '@opentelemetry/sdk-trace-web';
import {OTLPTraceExporter} from '@opentelemetry/exporter-trace-otlp-http';
import {ZoneContextManager} from '@opentelemetry/context-zone';
import {Resource} from '@opentelemetry/resources';
import {ATTR_SERVICE_NAME, ATTR_SERVICE_VERSION} from '@opentelemetry/semantic-conventions';

/**
 * Initialize OpenTelemetry browser telemetry.
 *
 * Instruments:
 * - All fetch() calls (API requests) via manual span creation
 * - Custom spans for SSE connection lifecycle
 *
 * Traces are exported to the backend's OTLP endpoint.
 * In production, the backend forwards them to GCP Cloud Trace.
 */
export function initTelemetry() {
  // Only enable telemetry when an absolute OTLP endpoint is configured
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const otlpEndpoint = (import.meta as any).env?.VITE_OTEL_ENDPOINT as string | undefined;
  if (!otlpEndpoint) {
    console.debug('[OTel] Telemetry disabled (set VITE_OTEL_ENDPOINT to enable)');
    return;
  }

  const resource = new Resource({
    [ATTR_SERVICE_NAME]: 'executespec-ui',
    [ATTR_SERVICE_VERSION]: '0.1.0',
  });

  const exporter = new OTLPTraceExporter({
    url: otlpEndpoint,
  });

  const provider = new WebTracerProvider({
    resource,
    spanProcessors: [new BatchSpanProcessor(exporter)],
  });

  provider.register({
    contextManager: new ZoneContextManager(),
  });

  console.debug('[OTel] Browser telemetry initialized');
}
