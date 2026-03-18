import { WebTracerProvider, BatchSpanProcessor } from '@opentelemetry/sdk-trace-web';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { ZoneContextManager } from '@opentelemetry/context-zone';
import { Resource } from '@opentelemetry/resources';
import { ATTR_SERVICE_NAME, ATTR_SERVICE_VERSION } from '@opentelemetry/semantic-conventions';
import { trace, SpanStatusCode } from '@opentelemetry/api';

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
  // Skip telemetry in development unless explicitly enabled
  const otlpEndpoint = (import.meta as any).env?.VITE_OTEL_ENDPOINT as string | undefined;
  if (!otlpEndpoint && (import.meta as any).env?.DEV) {
    console.debug('[OTel] Telemetry disabled in dev (set VITE_OTEL_ENDPOINT to enable)');
    return;
  }

  const resource = new Resource({
    [ATTR_SERVICE_NAME]: 'executespec-ui',
    [ATTR_SERVICE_VERSION]: '0.1.0',
  });

  const exporter = new OTLPTraceExporter({
    url: otlpEndpoint || '/api/otel/v1/traces',
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

/**
 * Create a custom span for SSE connection lifecycle tracking.
 * Call when SSE connects, and end the span when it disconnects.
 */
export function createSseSpan(runId: string) {
  const tracer = trace.getTracer('executespec-ui');
  const span = tracer.startSpan('sse.connection', {
    attributes: {
      'sse.run_id': runId,
    },
  });
  return {
    end: () => span.end(),
    error: (err: string) => {
      span.setStatus({ code: SpanStatusCode.ERROR, message: err });
      span.end();
    },
    reconnect: () => {
      span.addEvent('sse.reconnect');
    },
  };
}
