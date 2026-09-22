/**
 * Every HTTP call the page makes, and the curl for each one.
 *
 * The curl string is built from the *same argument object* that is handed to fetch, a few lines
 * later in the same function. That is the whole point of this file existing: if the two were
 * built separately the page could show one command and send another, and a teaching aid that
 * lies about what it did is worse than no teaching aid. There is no path here that sends a
 * request without producing its curl.
 */

/** Filled in from GET /api/v1/ui-config before anything else runs. */
export const config = {
  ingest: '',
  delivery: '',
  receiver: '',
  activeProfiles: '',
};

export async function loadConfig() {
  // The page is served by ingest, so ingest is wherever the page came from. Only the other two
  // need discovering.
  config.ingest = window.location.origin;
  const response = await fetch(`${config.ingest}/api/v1/ui-config`);
  if (!response.ok) throw new Error(`ui-config returned ${response.status}`);
  const body = await response.json();
  config.delivery = body.deliveryBaseUrl;
  config.receiver = body.receiverBaseUrl;
  config.activeProfiles = body.activeProfiles || '';
  return config;
}

/** True when the CORS config the other two services need is actually switched on. */
export function demoProfileActive() {
  return config.activeProfiles.split(',').includes('demo');
}

function buildCurl({ method = 'GET', url, body }) {
  const parts = [];
  if (method !== 'GET') parts.push(`-X ${method}`);
  if (body !== undefined) parts.push(`-H 'Content-Type: application/json'`);
  const head = `curl -s${parts.length ? ' ' + parts.join(' ') : ''} ${shellQuote(url)}`;
  if (body === undefined) return head;
  return `${head} \\\n  -d ${shellQuote(JSON.stringify(body))}`;
}

function shellQuote(value) {
  // Single quotes, with the one escape sequence that works inside them.
  return `'${String(value).replace(/'/g, `'\\''`)}'`;
}

/**
 * Make a request and report everything about it: the curl a reader could paste, the status, and
 * the parsed body. Never throws for an HTTP error -- a 409 is an outcome this page wants to
 * display, not an exception.
 *
 * @returns {Promise<{curl: string, ok: boolean, status: number, body: any, error: string|null}>}
 */
export async function request({ method = 'GET', url, body, timeoutMs = 8000 }) {
  const curl = buildCurl({ method, url, body });
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const response = await fetch(url, {
      method,
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    });
    const text = await response.text();
    let parsed = null;
    try {
      parsed = text ? JSON.parse(text) : null;
    } catch {
      parsed = text;
    }
    return { curl, ok: response.ok, status: response.status, body: parsed, error: null };
  } catch (failure) {
    // Unreachable, CORS-blocked, or timed out. The page shows this rather than going blank.
    return { curl, ok: false, status: 0, body: null, error: describe(failure) };
  } finally {
    clearTimeout(timer);
  }
}

function describe(failure) {
  if (failure.name === 'AbortError') return 'timed out';
  if (failure instanceof TypeError) return 'unreachable (service down, or CORS blocked)';
  return String(failure.message || failure);
}

// ----------------------------------------------------------------- ingest

export const submitEvent = (event) =>
  request({ method: 'POST', url: `${config.ingest}/api/v1/events`, body: event });

export const listEvents = (size = 50) =>
  request({ url: `${config.ingest}/api/v1/events?page=0&size=${size}` });

export const getEvent = (eventId) =>
  request({ url: `${config.ingest}/api/v1/events/${encodeURIComponent(eventId)}` });

export const ingestHealth = () => request({ url: `${config.ingest}/actuator/health`, timeoutMs: 3000 });

// --------------------------------------------------------------- delivery

export const listDeliveries = (status) =>
  request({ url: `${config.delivery}/internal/v1/deliveries?status=${status}` });

export const deliveryHealth = () =>
  request({ url: `${config.delivery}/actuator/health`, timeoutMs: 3000 });

// --------------------------------------------------------------- receiver

export const setReceiverMode = (mode) =>
  request({ method: 'POST', url: `${config.receiver}/control/mode`, body: mode });

export const resetReceiver = () =>
  request({ method: 'POST', url: `${config.receiver}/control/reset` });

export const receiverState = () => request({ url: `${config.receiver}/control/received` });

export const receiverHealth = () =>
  request({ url: `${config.receiver}/actuator/health`, timeoutMs: 3000 });

/**
 * The event contract from the problem statement. Built here so the form, the "send again"
 * button and the guided scenarios all submit the same shape.
 */
export function buildEvent({ eventId, type, severity, payload }) {
  let parsed;
  try {
    parsed = JSON.parse(payload);
  } catch {
    throw new Error('The payload is not valid JSON.');
  }
  if (severity) parsed.severity = severity;
  return { eventId, type, occurredAt: new Date().toISOString(), payload: parsed };
}
