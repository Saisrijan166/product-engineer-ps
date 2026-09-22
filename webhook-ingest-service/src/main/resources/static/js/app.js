/**
 * Wiring: polling, rendering, and the live five-step diagram.
 *
 * Written for a reader who has never heard the words "webhook" or "idempotent". The rule
 * throughout: plain words in the interface, technical terms behind a toggle or in the Explain
 * panel, and never an unexplained acronym.
 *
 * Every call goes through api.js, which returns the curl alongside the response, so the "what
 * this ran" details cannot drift from what was actually sent.
 */
import * as api from './api.js';
import { scenarios } from './scenarios.js';

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => [...document.querySelectorAll(sel)];
const el = (tag, cls, text) => {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text !== undefined) node.textContent = text;
  return node;
};

const state = {
  events: [],
  selected: null,
  detail: null,
  knownIds: new Set(),
  justArrived: null,
  workers: new Set(),
  scenarioRunning: false,
  captionLocked: false,
  pollFailures: 0,
};

// ------------------------------------------------- plain-English wording

/**
 * The whole sentence for one attempt, in words rather than codes.
 *
 * Phrased as "they ..." rather than repeating "the message", because the screen is already about
 * one message and saying it twice in a sentence reads badly.
 */
function sentenceFor(attempt) {
  if (attempt.errorClass === 'CIRCUIT_OPEN') {
    return 'We did not send it this time. Several recent attempts to them had failed, so we were '
      + 'deliberately giving them a rest.';
  }
  return `We sent it, and ${saidWhat(attempt)}.`;
}

function saidWhat(attempt) {
  if (attempt.httpStatus) {
    const known = {
      200: 'they accepted it', 201: 'they accepted it',
      202: 'they accepted it', 204: 'they accepted it',
      400: 'they said it was malformed',
      401: 'they said we are not authorised',
      403: 'they refused us access',
      404: 'that address does not exist',
      405: 'they do not accept anything sent that way',
      409: 'they said it conflicts with something they already have',
      410: 'that address is gone for good',
      413: 'they said it was too big',
      422: 'they rejected its contents',
      408: 'they ran out of time waiting for us',
      425: 'they said we were too early',
      429: 'they asked us to slow down',
      500: 'something broke on their end',
      501: 'they cannot handle this kind of request',
      502: 'something between us and them failed',
      503: 'they said they were too busy right now',
      504: 'something in between took too long',
      505: 'they do not support how we are talking to them',
    };
    return known[attempt.httpStatus] || `they answered with code ${attempt.httpStatus}`;
  }
  const byError = {
    READ_TIMEOUT: 'they never answered in time',
    CONNECT_TIMEOUT: 'we could not get a connection in time',
    CONNECTION_REFUSED: 'nothing was listening at that address',
    DNS_FAILURE: 'we could not find their address at all',
    TLS_ERROR: 'their security certificate would not check out',
    MALFORMED_URL: 'their address is not a usable web address',
    UNKNOWN: 'the connection failed for an unclear reason',
  };
  return byError[attempt.errorClass] || 'we got no usable answer';
}

/** What happened next, as a consequence the reader can follow. */
function whatNext(attempt, index, attempts, delivery) {
  const isLast = index === attempts.length - 1;
  if (attempt.outcome === 'SUCCESS') return { text: 'Delivered. Nothing more to do.', tone: 'ok' };

  if (!isLast) {
    const wait = gapAfter(index, attempts);
    return {
      text: wait === null
        ? 'We tried again shortly afterwards.'
        : `We waited ${humanGap(wait)}, then tried again.`,
      tone: 'warn',
    };
  }
  if (delivery.status === 'FAILED_PERMANENT') {
    return {
      text: 'We stopped here on purpose. Trying again would get the same answer, so there was '
        + `no point — and ${delivery.maxAttempts - delivery.attemptCount} of our `
        + `${delivery.maxAttempts} tries were left unused.`,
      tone: 'bad',
    };
  }
  if (delivery.status === 'FAILED_EXHAUSTED') {
    return { text: `That was try ${delivery.maxAttempts} of ${delivery.maxAttempts}. We stopped, `
      + 'and kept this record so you can see why.', tone: 'bad' };
  }
  if (delivery.nextAttemptAt) {
    const seconds = Math.max(0, Math.ceil((new Date(delivery.nextAttemptAt) - Date.now()) / 1000));
    return { text: seconds > 0 ? `Waiting ${seconds} more second${seconds === 1 ? '' : 's'}, then we try again.`
      : 'Trying again now…', tone: 'warn' };
  }
  return { text: 'Trying again shortly.', tone: 'warn' };
}

function gapAfter(index, attempts) {
  const next = attempts[index + 1];
  const here = attempts[index];
  if (!next || !here.completedAt) return null;
  return Math.max(0, new Date(next.startedAt) - new Date(here.completedAt));
}

const humanGap = (ms) => ms < 950 ? 'under a second'
  : ms < 60000 ? `${(ms / 1000).toFixed(ms < 10000 ? 1 : 0)} seconds`
  : `${Math.round(ms / 60000)} minutes`;

/** Where the message got to, in words. Used by the table and the story header. */
const STATUS_WORDS = {
  PENDING: 'Just arrived',
  IN_FLIGHT: 'Sending',
  RETRY_SCHEDULED: 'Will retry',
  SUCCEEDED: 'Delivered',
  FAILED_PERMANENT: 'Rejected',
  FAILED_EXHAUSTED: 'Gave up',
};

const VERDICT = {
  SUCCEEDED: 'It got there.',
  FAILED_PERMANENT: 'It was refused, and trying again would not have helped.',
  FAILED_EXHAUSTED: 'It never got there. We tried five times and stopped.',
  RETRY_SCHEDULED: 'Not there yet — still trying.',
  IN_FLIGHT: 'On its way right now.',
  PENDING: 'Stored, about to be sent.',
};

const clockTime = (iso) => new Date(iso).toLocaleTimeString([], {
  hour: '2-digit', minute: '2-digit', second: '2-digit',
});

// ------------------------------------------------------------------ theme

function initTheme() {
  const saved = localStorage.getItem('wre-theme');
  const preferred = window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
  document.documentElement.dataset.theme = saved || preferred;
  $('#theme-toggle').addEventListener('click', () => {
    const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = next;
    localStorage.setItem('wre-theme', next);
  });
}

// ------------------------------------------------- outcome + curl display

/**
 * A plain sentence about what just happened, plus where to look next, plus the exact command
 * behind it for anyone who wants it.
 */
function outcomeBlock({ tone, said, next, curl, result }) {
  const wrap = el('div');
  const box = el('div', `outcome ${tone}`);
  box.append(el('div', null, said));
  if (next) {
    const line = el('span', 'next');
    line.append(document.createTextNode('Where to look: '));
    line.append(el('b', null, next));
    box.append(line);
  }
  wrap.append(box);
  wrap.append(curlDetails(curl, result));
  return wrap;
}

/** The one place a curl command is rendered. Collapsed by default -- it is for engineers. */
function curlDetails(curl, result) {
  const box = el('details', 'curl');
  box.append(el('summary', 'curl-label', 'show the command this ran'));
  box.append(el('pre', null, curl));
  const copy = el('button', 'btn ghost copy', 'copy');
  copy.addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(curl);
      copy.textContent = 'copied';
      setTimeout(() => { copy.textContent = 'copy'; }, 1200);
    } catch {
      copy.textContent = 'select it manually';
    }
  });
  box.append(copy);

  if (result) {
    const resp = el('div', 'resp');
    const bad = result.error || result.status >= 400;
    resp.append(el('div', `curl-label ${bad ? 'bad' : 'ok'}`,
      result.error ? `failed — ${result.error}` : `answered ${result.status}`));
    if (result.body !== null && result.body !== undefined && result.body !== '') {
      resp.append(el('pre', null, typeof result.body === 'string'
        ? result.body : JSON.stringify(result.body, null, 2)));
    }
    box.append(resp);
  }
  return box;
}

const show = (container, node) => container.replaceChildren(node);

function flash(node) {
  if (!node) return;
  node.classList.remove('flash');
  void node.offsetWidth;
  node.classList.add('flash');
}

// ----------------------------------------------------------------- health

async function pollHealth() {
  const [ingest, delivery, receiver] = await Promise.all([
    api.ingestHealth(), api.deliveryHealth(), api.receiverHealth(),
  ]);

  setDot('ingest', ingest.ok ? 'up' : 'down', '');
  setDot('delivery', delivery.ok ? 'up' : 'down', workerHint());
  setDot('receiver', receiver.ok ? 'up' : 'down', '');

  // Storage has no page of its own; both services report on it, and either one seeing it is
  // enough to know it is reachable.
  const db = dbStatus(ingest) || dbStatus(delivery);
  setDot('postgres', db === 'UP' ? 'up' : db ? 'down' : 'warn', db ? '' : 'not reported');

  const notes = [];
  if (!ingest.ok) notes.push('Intake is down. Nothing new can be accepted; anything already accepted is safe.');
  if (!delivery.ok) notes.push('Sender is down. Messages are still accepted and go out when it returns.');
  if (!receiver.ok) notes.push('Test customer is down. Deliveries fail and retry as usual.');
  if (db && db !== 'UP') notes.push('Storage is unreachable. Everything pauses until it returns.');
  if (!api.demoProfileActive()) {
    notes.push('This page was opened against a stack started without the demo setting, so the '
      + 'browser will block some of its requests. Restart with SPRING_PROFILES_ACTIVE=demo.');
  }
  $('#status-note').textContent = notes.join(' ');
}

const dbStatus = (health) => health.ok ? health.body?.components?.db?.status ?? null : null;
const workerHint = () => state.workers.size > 1 ? `${state.workers.size} copies running` : '';

function setDot(service, cls, hint) {
  const node = document.querySelector(`.status[data-svc="${service}"]`);
  node.querySelector('.dot').className = `dot ${cls}`;
  node.querySelector('.hint').textContent = hint || '';
}

// ------------------------------------------------------------------ events

async function pollEvents() {
  const result = await api.listEvents(15);
  if (!result.ok) {
    state.pollFailures += 1;
    if (state.pollFailures > 1) {
      banner('Cannot reach the message intake service just now. Still trying — what you see below '
        + 'is the last known state.');
    }
    return;
  }
  state.pollFailures = 0;
  banner(null);
  state.events = result.body?.events || [];
  $('#events-count').textContent = state.events.length ? `— ${result.body.totalEvents} in total` : '';
  renderEvents();

  if (state.selected) await refreshDetail();
  await refreshDeadLetters();
}

function renderEvents() {
  const body = $('#events-table tbody');
  if (!state.events.length) {
    body.replaceChildren(rowMessage('Nothing yet — send a message, or run a scenario.'));
    return;
  }
  body.replaceChildren(...state.events.map((event) => {
    const detail = state.detail?.eventId === event.eventId ? state.detail : null;
    const delivery = detail?.delivery;
    const status = delivery?.status || 'PENDING';

    const tr = el('tr', 'clickable');
    if (state.selected === event.eventId) tr.classList.add('selected');
    if (state.justArrived === event.eventId) tr.classList.add('justArrived');
    tr.append(cell(event.eventId, 'mono'));
    tr.append(cell(event.type));

    const statusCell = el('td');
    statusCell.append(el('span', `badge s-${status}`, STATUS_WORDS[status] || status));
    if (event.duplicateSubmissionCount > 0) {
      statusCell.append(el('span', 'muted', ` sent ${event.duplicateSubmissionCount + 1}×`));
    }
    tr.append(statusCell);

    tr.append(cell(delivery ? `${delivery.attemptCount} of ${delivery.maxAttempts}` : '—'));
    tr.append(cell(countdownText(delivery?.nextAttemptAt)));
    tr.append(cell(age(event.receivedAt)));
    tr.addEventListener('click', () => selectEvent(event.eventId));
    return tr;
  }));
}

const cell = (text, cls) => el('td', cls, text);
function rowMessage(text) {
  const tr = el('tr');
  const td = el('td', 'muted', text);
  td.colSpan = 6;
  tr.append(td);
  return tr;
}

function countdownText(nextAttemptAt) {
  if (!nextAttemptAt) return '—';
  const seconds = Math.round((new Date(nextAttemptAt) - Date.now()) / 1000);
  return seconds > 0 ? `in ${seconds}s` : 'now';
}

function age(iso) {
  const seconds = Math.max(0, Math.round((Date.now() - new Date(iso)) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  return `${Math.floor(seconds / 3600)}h ago`;
}

async function selectEvent(eventId, { scroll = true } = {}) {
  state.selected = eventId;
  await refreshDetail();
  renderEvents();
  if (scroll) $('#detail-card').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

async function refreshDetail() {
  const result = await api.getEvent(state.selected);
  if (!result.ok) return;
  state.detail = result.body;
  (result.body?.delivery?.attempts || []).forEach((a) => a.workerId && state.workers.add(a.workerId));
  renderStory(result);
  updateDiagram(result.body);
}

// ------------------------------------------------------- the story screen

function renderStory(result) {
  const event = result.body;
  $('#detail-card').hidden = false;
  $('#detail-id').textContent = `— ${event.eventId}`;

  const body = el('div');
  const delivery = event.delivery;

  if (!delivery) {
    body.append(el('p', null, event.deliveryLookupError
      ? 'We cannot reach the part of the system that does the sending, so we cannot show its '
        + 'attempts. The message itself is stored and safe — everything above came from a '
        + 'different service that is working fine.'
      : 'This message is stored and about to be handed over for sending. Its attempts will '
        + 'appear here within a second or two.'));
    body.append(curlDetails(result.curl, null));
    show($('#detail-body'), body);
    return;
  }

  const head = el('div', 'story-head');
  head.append(el('span', 'story-verdict', VERDICT[delivery.status] || delivery.status));
  head.append(el('span', 'muted', `${delivery.attemptCount} of ${delivery.maxAttempts} tries used`));
  const ref = el('span', 'story-ref');
  ref.append(document.createTextNode('Every try carried the same reference, '));
  ref.append(el('code', 'mono', delivery.deliveryId));
  ref.append(document.createTextNode(', so the receiving system can spot a repeat and ignore it. '
    + 'That is how we avoid delivering the same thing twice by accident.'));
  head.append(ref);
  body.append(head);

  body.append(renderTries(delivery));
  body.append(curlDetails(result.curl, null));
  show($('#detail-body'), body);
}

/**
 * One paragraph per attempt, in order, with the wait between them drawn to scale.
 *
 * The scale is the point. Growing pauses as a column of numbers is something you have to read and
 * compare; as bars it is something you see at a glance.
 */
function renderTries(delivery) {
  const attempts = delivery.attempts || [];
  const wrap = el('div');
  if (!attempts.length) {
    wrap.append(el('p', 'muted', 'No attempts yet.'));
    return wrap;
  }

  const gaps = attempts.map((_, i) => gapAfter(i, attempts));
  const widest = Math.max(1, ...gaps.filter((g) => g !== null));

  attempts.forEach((attempt, i) => {
    const tone = attempt.outcome === 'SUCCESS' ? 'SUCCEEDED'
      : attempt.outcome === 'PERMANENT_FAILURE' ? 'FAILED_PERMANENT' : 'RETRY_SCHEDULED';

    const row = el('div', 'try');
    const rail = el('div', 'try-rail');
    rail.append(el('div', `try-num s-${tone}`, String(attempt.attemptNumber)));
    if (i < attempts.length - 1) rail.append(el('div', 'try-rail-line'));
    row.append(rail);

    const detail = el('div', 'try-body');
    detail.append(el('div', 'try-when', `Try ${attempt.attemptNumber} · ${clockTime(attempt.startedAt)}`));
    detail.append(el('div', 'try-said', sentenceFor(attempt)));

    const next = whatNext(attempt, i, attempts, delivery);
    const nextLine = el('div', 'try-next');
    nextLine.append(el('b', null, next.text));
    detail.append(nextLine);

    detail.append(techDetails(attempt));
    row.append(detail);
    wrap.append(row);

    const gap = gaps[i];
    if (gap !== null) {
      const waitRow = el('div', 'wait');
      const waitRail = el('div', 'wait-rail');
      waitRail.append(el('span'));
      waitRow.append(waitRail);
      const waitBody = el('div', 'wait-body');
      const bar = el('div', 'wait-bar');
      bar.style.width = `${Math.max(3, (gap / widest) * 100)}%`;
      waitBody.append(bar);
      waitBody.append(el('span', 'wait-label', `waited ${humanGap(gap)}`));
      waitRow.append(waitBody);
      wrap.append(waitRow);
    }
  });

  if (gaps.filter((g) => g !== null).length > 1) {
    wrap.append(el('p', 'wait-note', 'The amber bars are to scale against each other, so you can '
      + 'see each wait being longer than the last. That is deliberate: a system that is '
      + 'struggling gets more room to recover each time, rather than being poked at the same rate.'));
  }
  return wrap;
}

/** Status codes, timings and machine names -- off by default, because most readers do not want them. */
function techDetails(attempt) {
  const box = el('details', 'tech');
  box.append(el('summary', null, 'technical details'));
  const grid = el('dl', 'tech-grid');
  const add = (label, value) => {
    if (value === null || value === undefined || value === '') return;
    grid.append(el('dt', null, label));
    grid.append(el('dd', null, String(value)));
  };
  add('result', attempt.outcome);
  add('HTTP status', attempt.httpStatus);
  add('failure type', attempt.errorClass);
  add('error', attempt.errorMessage);
  add('took', attempt.durationMs === null ? null : `${attempt.durationMs} ms`);
  add('their reply', attempt.responseBodySnippet);
  add('they asked us to wait', attempt.retryAfterSeconds ? `${attempt.retryAfterSeconds}s` : null);
  add('next try due', attempt.nextAttemptAt ? clockTime(attempt.nextAttemptAt) : null);
  add('ran on', attempt.workerId);
  box.append(grid);
  return box;
}

// ----------------------------------------------------------------- diagram

const STAGES = ['st-app', 'st-saved', 'st-queue', 'st-sending', 'st-arrived'];

function updateDiagram(event) {
  STAGES.forEach((id) => $(`#${id}`).classList.remove('active', 'done', 'failed', 'waiting'));
  if (!event) return;

  const mark = (id, cls) => $(`#${id}`).classList.add(cls);
  mark('st-app', 'done');
  mark('st-saved', 'done');

  const dispatched = event.dispatch?.status === 'DISPATCHED';
  if (!dispatched) {
    if (event.dispatch?.status === 'FAILED') {
      mark('st-queue', 'failed');
      caption('Saved, but we could not hand it over to the part that sends it. The message is '
        + 'still stored — nothing has been lost.');
    } else {
      mark('st-queue', 'active');
      caption('Saved so it cannot be lost. Now being handed over for sending.');
    }
    return;
  }

  const delivery = event.delivery;
  if (!delivery) {
    mark('st-queue', 'active');
    caption('Handed over. Waiting to hear what happened.');
    return;
  }

  switch (delivery.status) {
    case 'PENDING':
      mark('st-queue', 'active');
      caption('In line to be sent. A worker will pick it up in a moment.');
      break;
    case 'IN_FLIGHT':
      mark('st-queue', 'done'); mark('st-sending', 'active'); mark('st-arrived', 'active');
      caption(`Sending it now — this is try ${delivery.attemptCount}.`);
      break;
    case 'RETRY_SCHEDULED': {
      mark('st-sending', 'failed'); mark('st-arrived', 'failed'); mark('st-queue', 'waiting');
      const seconds = delivery.nextAttemptAt
        ? Math.max(0, Math.ceil((new Date(delivery.nextAttemptAt) - Date.now()) / 1000)) : null;
      caption(`Try ${delivery.attemptCount} of ${delivery.maxAttempts} did not get through, but it `
        + 'is the kind of failure that is worth retrying. Back in line'
        + (seconds !== null ? `, trying again in about ${seconds} second${seconds === 1 ? '' : 's'}.` : '.'));
      break;
    }
    case 'SUCCEEDED':
      ['st-queue', 'st-sending', 'st-arrived'].forEach((id) => mark(id, 'done'));
      caption(delivery.attemptCount === 1
        ? 'Delivered first time. Their system confirmed it.'
        : `Delivered on try ${delivery.attemptCount}. The earlier failures are all recorded below.`);
      break;
    case 'FAILED_PERMANENT':
      mark('st-queue', 'done'); mark('st-sending', 'failed'); mark('st-arrived', 'failed');
      caption('Their system refused the message outright. Trying again would get the same answer, '
        + 'so we stopped straight away rather than wasting four more tries.');
      break;
    case 'FAILED_EXHAUSTED':
      // Queueing is marked done, not failed: it worked every time, five times over. Colouring it
      // red alongside the other two read as "waiting its turn failed", which never happened -- and
      // it is what the permanent-failure branch above already does.
      mark('st-queue', 'done'); mark('st-sending', 'failed'); mark('st-arrived', 'failed');
      caption(`We tried ${delivery.maxAttempts} times over a growing series of pauses and it never `
        + 'got through, so we stopped and kept the whole history.');
      break;
    default:
      break;
  }
}

function caption(text) {
  if (state.captionLocked) return;
  $('#flow-caption').textContent = text;
}

/** The waiting box shows a live countdown; the table's "next try" column ticks with it. */
function tickCountdown() {
  const delivery = state.detail?.delivery;
  const node = $('#queue-countdown');
  if (delivery?.status === 'RETRY_SCHEDULED' && delivery.nextAttemptAt) {
    const seconds = Math.max(0, Math.ceil((new Date(delivery.nextAttemptAt) - Date.now()) / 1000));
    node.textContent = seconds > 0 ? `next try in ${seconds}s` : 'trying again now…';
  } else {
    node.textContent = 'in line to be sent';
  }

  $$('#events-table tbody tr').forEach((tr, i) => {
    const event = state.events[i];
    if (!event) return;
    const detail = state.detail?.eventId === event.eventId ? state.detail : null;
    if (detail?.delivery?.nextAttemptAt) {
      tr.children[4].textContent = countdownText(detail.delivery.nextAttemptAt);
    }
  });
}

// ------------------------------------------------------------ gave-up list

async function refreshDeadLetters() {
  const [exhausted, permanent] = await Promise.all([
    api.listDeliveries('FAILED_EXHAUSTED'), api.listDeliveries('FAILED_PERMANENT'),
  ]);
  const rows = [...(exhausted.body?.deliveries || []), ...(permanent.body?.deliveries || [])];
  const container = $('#deadletter');

  if (!exhausted.ok && !permanent.ok) {
    show(container, el('p', 'muted', 'Cannot reach the sending service just now, so this list is unavailable.'));
    return;
  }
  if (!rows.length) {
    show(container, el('p', 'muted', 'None. Run scenario 3 to create one.'));
    return;
  }

  const table = el('table');
  const head = el('thead');
  const hr = el('tr');
  ['Reference', 'What happened', 'Tries', 'Why we stopped'].forEach((h) => hr.append(el('th', null, h)));
  head.append(hr);
  table.append(head);

  const tbody = el('tbody');
  rows.forEach((d) => {
    const tr = el('tr', 'clickable');
    tr.append(cell(d.eventId, 'mono'));
    const status = el('td');
    status.append(el('span', `badge s-${d.status}`, STATUS_WORDS[d.status] || d.status));
    tr.append(status);
    tr.append(cell(`${d.attemptCount} of ${d.maxAttempts}`));
    tr.append(cell(d.terminalReason === 'ATTEMPTS_EXHAUSTED'
      ? 'Five tries used up. Carrying on forever would tie up capacity other messages need.'
      : 'Refused in a way that would not change on a retry, so we stopped at once.'));
    tr.addEventListener('click', () => selectEvent(d.eventId));
    tbody.append(tr);
  });
  table.append(tbody);
  const wrap = el('div', 'table-scroll');
  wrap.append(table);
  show(container, wrap);
}

// ---------------------------------------------------- receiving system

async function applyMode(mode, describe) {
  const result = await api.setReceiverMode(mode);
  await refreshReceiverMode();
  show($('#receiver-result'), outcomeBlock({
    tone: result.ok ? 'warn' : 'bad',
    said: result.ok
      ? `Done — the test customer system will now ${describe}.`
      : `Could not change it: ${result.error || `it answered ${result.status}`}.`,
    next: result.ok ? 'the "Send a message yourself" panel — send one, then watch the five steps at the top of the page' : null,
    curl: result.curl,
    result,
  }));
  return result;
}

async function refreshReceiverMode() {
  const result = await api.receiverState();
  if (!result.ok) {
    $('#receiver-mode').textContent = 'cannot reach it';
    return;
  }
  const mode = result.body?.mode;
  const words = {
    ALWAYS_OK: 'accept everything',
    ALWAYS_FAIL: `always fail (${mode?.status})`,
    FAIL_N_THEN_OK: `refuse ${mode?.failCount}×, then accept`,
    TIMEOUT: 'never answer',
  };
  const label = words[mode?.mode] || mode?.mode || '?';
  $('#receiver-mode').textContent = label;
  $('#receiver-mode-label').textContent = label;
}

// -------------------------------------------------------------- scenarios

function buildScenarioButtons() {
  const container = $('#scenario-buttons');
  container.replaceChildren(...scenarios.map((scenario, i) => {
    const button = el('button', 'btn');
    button.append(document.createTextNode(`Show me scenario ${i + 1}: ${scenario.title}`));
    button.append(el('span', 'tag', scenario.tag));
    button.title = scenario.blurb;
    button.dataset.scenario = scenario.id;
    button.addEventListener('click', () => runScenario(scenario, button, i + 1));
    return button;
  }));
}

async function runScenario(scenario, button, number) {
  if (state.scenarioRunning) return;
  state.scenarioRunning = true;
  const original = button.textContent;
  $$('#scenario-buttons .btn').forEach((b) => { b.disabled = true; });
  button.textContent = `Running scenario ${number}…`;

  const panel = $('#narration');
  panel.replaceChildren();
  const line = (cls, text) => {
    const node = el('p', cls, text);
    panel.append(node);
    panel.scrollTop = panel.scrollHeight;
    return node;
  };
  line('n-note', scenario.blurb);

  let failures = 0;
  const say = {
    step: (text) => { line('n-step', text); state.captionLocked = false; },
    note: (text) => line('n-note', text),
    /** A scenario can narrate the diagram directly while it drives it. */
    stage: (text) => { state.captionLocked = false; caption(text); state.captionLocked = true; },
    curl: (curl) => { panel.append(curlDetails(curl, null)); panel.scrollTop = panel.scrollHeight; },
    observe: (what, { passed, detail }) => {
      if (!passed) failures += 1;
      const node = line(passed ? 'n-pass' : 'n-fail', what);
      node.append(el('span', 'why', detail));
    },
    focus: (eventId) => { state.justArrived = eventId; selectEvent(eventId, { scroll: false }); },
  };

  // Poll faster while a scenario runs, so the five steps animate one at a time instead of
  // jumping several states between ticks.
  const fastPoll = setInterval(() => { if (state.selected) refreshDetail(); }, 500);

  try {
    await scenario.run(say);
    state.captionLocked = false;
    line(failures ? 'n-done bad' : 'n-done',
      failures ? `${failures} thing(s) did not match what we promised.`
        : 'Everything matched what this system promises to do.');
    const pointer = line('n-pointer', '');
    pointer.append(document.createTextNode('Where to look next: the message it created is the '));
    pointer.append(el('b', null, 'highlighted row'));
    pointer.append(document.createTextNode(' in the Messages list, and its full try-by-try story '
      + 'is in '));
    pointer.append(el('b', null, 'The full story'));
    pointer.append(document.createTextNode(' below that.'));
    flash($('#detail-card'));
  } catch (failure) {
    state.captionLocked = false;
    line('n-done bad', `The scenario could not finish: ${failure.message}`);
  } finally {
    clearInterval(fastPoll);
    state.scenarioRunning = false;
    $$('#scenario-buttons .btn').forEach((b) => { b.disabled = false; });
    button.textContent = original;
    await pollEvents();
    await refreshReceiverMode();
  }
}

// ------------------------------------------------------------------- form

const suggestId = () => `msg_${Math.random().toString(36).slice(2, 8)}`;

function initForm() {
  $('#f-eventId').value = suggestId();
  $('#new-id').addEventListener('click', () => { $('#f-eventId').value = suggestId(); });
  $('#send-form').addEventListener('submit', (e) => { e.preventDefault(); send(); });
  $('#send-again').addEventListener('click', () => send());
}

async function send() {
  let event;
  try {
    event = api.buildEvent({
      eventId: $('#f-eventId').value.trim(),
      type: $('#f-type').value.trim(),
      severity: $('#f-severity').value,
      payload: $('#f-payload').value,
    });
  } catch (failure) {
    show($('#send-result'), el('p', 'outcome bad', `${failure.message} Fix the details box and try again.`));
    return;
  }

  const isRepeat = state.knownIds.has(event.eventId);
  const result = await api.submitEvent(event);
  state.knownIds.add(event.eventId);

  const said = {
    201: 'Accepted. This is a new message, and it is now stored — even if everything here '
      + 'restarted this second, it would still be delivered.',
    200: 'Accepted again, and deliberately not sent twice. We already had this reference, so you '
      + 'get the original message back rather than a second copy. This is the behaviour that '
      + 'stops a customer being told the same thing twice.',
    409: 'Refused. That reference has been used before, but with different contents. We will not '
      + 'guess which one you meant — a reference is supposed to identify one message, so we ask '
      + 'you to sort it out rather than silently picking one.',
  }[result.status];

  show($('#send-result'), outcomeBlock({
    tone: result.status === 201 ? 'ok' : result.status === 200 ? 'warn' : 'bad',
    said: said || (result.error
      ? `Could not send it: ${result.error}.`
      : `The service answered ${result.status}.`),
    next: result.status === 409 ? 'press "New reference", then Send again'
      : 'the highlighted top row of the Messages list, then the five steps at the top of the page',
    curl: result.curl,
    result,
  }));

  if (result.status === 201 || result.status === 200) {
    state.justArrived = event.eventId;
    await pollEvents();
    await selectEvent(event.eventId, { scroll: false });
    flash($('#events-card'));
    setTimeout(() => { state.justArrived = null; }, 2600);
  }
  if (isRepeat && result.status === 200) {
    $('#f-eventId').focus();
  }
}

// ------------------------------------------------------------------- misc

function banner(text) {
  const node = $('#banner');
  node.hidden = !text;
  node.textContent = text || '';
}

function initLearn() {
  const open = (yes) => {
    $('#learn').hidden = !yes;
    $('#learn-scrim').hidden = !yes;
    $('#learn-toggle').setAttribute('aria-expanded', String(yes));
  };
  $('#learn-toggle').addEventListener('click', () => open($('#learn').hidden));
  $('#learn-close').addEventListener('click', () => open(false));
  $('#learn-scrim').addEventListener('click', () => open(false));
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') open(false); });
}

function initReceiverControls() {
  const words = {
    ALWAYS_OK: 'accept every message',
    TIMEOUT: 'accept the connection and then never answer',
  };
  $$('[data-mode]').forEach((button) => {
    button.addEventListener('click', () => {
      const mode = JSON.parse(button.dataset.mode);
      const describe = mode.mode === 'ALWAYS_FAIL'
        ? (mode.status === 422 ? 'reject every message outright' : 'fail on every attempt')
        : words[mode.mode] || 'behave as selected';
      applyMode(mode, describe);
    });
  });

  $('#apply-failn').addEventListener('click', () => {
    const failCount = Number($('#f-failcount').value) || 1;
    const status = Number($('#f-failstatus').value);
    const mode = { mode: 'FAIL_N_THEN_OK', failCount, status };
    const retryAfter = Number($('#f-retryafter').value);
    if (retryAfter > 0) mode.retryAfterSeconds = retryAfter;
    applyMode(mode, `refuse the first ${failCount} attempt${failCount === 1 ? '' : 's'} and then accept`
      + (retryAfter > 0 ? `, asking us to wait ${retryAfter}s each time` : ''));
  });

  $('#receiver-reset').addEventListener('click', async () => {
    const result = await api.resetReceiver();
    await refreshReceiverMode();
    show($('#receiver-result'), outcomeBlock({
      tone: 'ok',
      said: 'Back to normal. Its record of what it received is cleared.',
      next: 'the "Send a message yourself" panel — the next one should be delivered first time',
      curl: result.curl,
      result,
    }));
  });
}

// ------------------------------------------------------------------- boot

async function main() {
  initTheme();
  initLearn();
  initForm();
  initReceiverControls();
  buildScenarioButtons();

  try {
    await api.loadConfig();
  } catch (failure) {
    banner(`Could not work out where the other services are (${failure.message}). This page needs `
      + 'the message intake service to tell it, and that request failed.');
    return;
  }
  if (!api.demoProfileActive()) {
    banner('This stack was started without the demo setting, so the browser will block this '
      + "page's requests to two of the services. Restart it with SPRING_PROFILES_ACTIVE=demo.");
  }

  await Promise.all([pollHealth(), pollEvents(), refreshReceiverMode()]);
  setInterval(pollHealth, 3000);
  setInterval(pollEvents, 3000);
  setInterval(tickCountdown, 250);
}

main();
