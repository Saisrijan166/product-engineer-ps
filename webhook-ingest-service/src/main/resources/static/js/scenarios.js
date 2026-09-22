/**
 * Five short stories, each one click.
 *
 * Each step does something and then says what it observed and whether that matched the promise.
 * The `expect` wording is written before the run, so a tick means the engine did what the README
 * claims — not merely that the page rendered something.
 *
 * These mirror scripts/verify-acceptance.sh. Same criteria, same assertions, different audience:
 * that script is for a reviewer with a terminal, this is for someone who has never heard the word
 * "webhook". The `tag` is the only jargon, kept so an engineer can map each one to the brief.
 */
import * as api from './api.js';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Poll until a condition holds, so a slow machine does not fail a scenario. */
async function until(eventId, predicate, { timeoutMs = 45000 } = {}) {
  const deadline = Date.now() + timeoutMs;
  let last = null;
  while (Date.now() < deadline) {
    const result = await api.getEvent(eventId);
    last = result.body;
    if (result.ok && predicate(last)) return last;
    await sleep(600);
  }
  return last;
}

const uniqueId = (prefix) => `${prefix}_${Date.now().toString(36)}`;

const messageFor = (eventId, severity = 'high') =>
  api.buildEvent({ eventId, type: 'incident.created', severity, payload: '{"incidentId":"inc_456"}' });

const check = (passed, detail) => ({ passed, detail });

export const scenarios = [
  {
    id: 'ac1',
    tag: 'AC1',
    title: 'a message that arrives first time',
    blurb: 'The straightforward case. The receiving system is working, so the message goes '
      + 'straight through on the first attempt.',
    async run(say) {
      const eventId = uniqueId('first');

      say.step('Setting the test customer system to accept everything.');
      say.curl((await api.setReceiverMode({ mode: 'ALWAYS_OK' })).curl);

      say.step(`Sending one message, reference ${eventId}.`);
      say.stage('Sending the message to be stored…');
      const submitted = await api.submitEvent(messageFor(eventId));
      say.curl(submitted.curl);
      say.observe('It was accepted and stored.',
        check(submitted.status === 201,
          'A brand new message is accepted straight away, before anything is sent. From this '
          + 'moment on it cannot be lost.'));
      say.focus(eventId);

      say.step('Now watching it go out.');
      const event = await until(eventId, (e) => e?.delivery?.status === 'SUCCEEDED');
      const delivery = event?.delivery || {};
      say.observe(`Delivered, and it took ${delivery.attemptCount} attempt.`,
        check(delivery.status === 'SUCCEEDED' && delivery.attemptCount === 1,
          'One attempt, one delivery. Nothing was retried because nothing failed.'));
      return eventId;
    },
  },

  {
    id: 'ac2',
    tag: 'AC2',
    title: 'the receiver is busy, so we try again',
    blurb: 'The common real-world case. The receiving system is temporarily too busy, then '
      + 'recovers — and this system keeps trying until it gets through.',
    async run(say) {
      const eventId = uniqueId('busy');

      say.step('Telling the test customer system to refuse the first two attempts, saying it is too busy.');
      say.curl((await api.setReceiverMode({ mode: 'FAIL_N_THEN_OK', failCount: 2, status: 503 })).curl);
      say.note('"Too busy" is a temporary problem, so it is worth trying again. Compare scenario 3, '
        + 'where the refusal is permanent and we stop immediately.');

      say.step(`Sending one message, reference ${eventId}.`);
      const submitted = await api.submitEvent(messageFor(eventId));
      say.curl(submitted.curl);
      say.focus(eventId);

      say.step('Watching it fail, wait, and try again. The pauses get longer each time.');
      const event = await until(eventId, (e) => e?.delivery?.status === 'SUCCEEDED');
      const attempts = event?.delivery?.attempts || [];
      say.observe(`It took ${attempts.length} attempts, and the third one got through.`,
        check(attempts.length === 3
          && attempts[0].outcome === 'RETRYABLE_FAILURE'
          && attempts[1].outcome === 'RETRYABLE_FAILURE'
          && attempts[2].outcome === 'SUCCESS',
          'Two failures then a success — and both failures are kept on the record rather than '
          + 'being overwritten by the eventual good news.'));
      say.note('The full story below shows the two pauses drawn to scale, so you can see the '
        + 'second one is longer than the first.');
      return eventId;
    },
  },

  {
    id: 'ac3',
    tag: 'AC3',
    title: 'the receiver stays broken, so we stop',
    blurb: 'The receiving system never recovers. This system tries five times over increasing '
      + 'pauses, then stops on purpose and records why — rather than retrying forever.',
    async run(say) {
      const eventId = uniqueId('broken');

      say.step('Telling the test customer system to fail every single time.');
      say.curl((await api.setReceiverMode({ mode: 'ALWAYS_FAIL', status: 500 })).curl);

      say.step(`Sending one message, reference ${eventId}.`);
      const submitted = await api.submitEvent(messageFor(eventId));
      say.curl(submitted.curl);
      say.focus(eventId);

      say.step('Waiting for all five attempts. This takes about fifteen seconds.');
      const event = await until(eventId, (e) => e?.delivery?.status === 'FAILED_EXHAUSTED');
      const delivery = event?.delivery || {};
      say.observe(`It gave up after ${delivery.attemptCount} of ${delivery.maxAttempts} attempts.`,
        check(delivery.status === 'FAILED_EXHAUSTED'
          && delivery.attemptCount === 5
          && delivery.terminalReason === 'ATTEMPTS_EXHAUSTED',
          'Exactly five, then it stopped — and it recorded that the reason was running out of '
          + 'attempts, not something else.'));

      say.step('Waiting another six seconds to prove it really has stopped.');
      await sleep(6000);
      const after = (await api.getEvent(eventId)).body;
      say.observe(`Still ${after?.delivery?.attemptCount} attempts — no sixth one appeared.`,
        check(after?.delivery?.attemptCount === 5,
          'It cannot try again. Once a message is finished with, the part of the system that '
          + 'picks up work simply cannot see it any more.'));

      const dead = await api.listDeliveries('FAILED_EXHAUSTED');
      say.curl(dead.curl);
      say.observe(`It is listed under "Dead Letters" — ${dead.body?.count ?? '?'} there now.`,
        check((dead.body?.count ?? 0) >= 1,
          'Giving up is visible and searchable. Nothing is quietly dropped.'));
      return eventId;
    },
  },

  {
    id: 'ac4',
    tag: 'AC4',
    title: 'the same message sent twenty times at once',
    blurb: 'Software retries. If the same message is sent twenty times simultaneously, the '
      + 'customer must still only be told once.',
    async run(say) {
      const eventId = uniqueId('twice');

      say.step('Setting the test customer system to accept everything, and clearing its records.');
      say.curl((await api.resetReceiver()).curl);

      say.step(`Sending the same message, reference ${eventId}, twenty times at the same instant.`);
      const message = messageFor(eventId);
      const results = await Promise.all(Array.from({ length: 20 }, () => api.submitEvent(message)));
      say.curl(results[0].curl);
      const created = results.filter((r) => r.status === 201).length;
      const duplicates = results.filter((r) => r.status === 200).length;
      say.observe(`One was told "new message", ${duplicates} were told "already have that one".`,
        check(created === 1 && duplicates === 19,
          'Exactly one of the twenty created it. There is no lock or coordination here — the '
          + 'database decides the winner, which is why it still works with several copies of the '
          + 'service running.'));

      say.step('Now waiting to see how many times it actually gets delivered.');
      const settled = await until(eventId, (e) => e?.delivery?.status === 'SUCCEEDED');
      say.observe(`Recorded as sent ${settled?.duplicateSubmissionCount + 1} times, delivered `
        + `with ${settled?.delivery?.attemptCount} attempt.`,
        check(settled?.duplicateSubmissionCount === 19 && settled?.delivery?.attemptCount === 1,
          'All twenty submissions are counted, and there is still only one delivery.'));
      say.focus(eventId);

      const seen = await api.receiverState();
      say.observe(`The customer system was contacted ${seen.body?.totalReceived} time.`,
        check(seen.body?.totalReceived === 1,
          'This is the point of the whole exercise: twenty attempts to tell them, one actual '
          + 'telling.'));

      say.step('Finally, the same reference but with different contents.');
      const conflicting = await api.submitEvent(
        api.buildEvent({ eventId, type: 'incident.created', severity: 'low', payload: '{"incidentId":"inc_999"}' }));
      say.curl(conflicting.curl);
      say.observe('Refused.',
        check(conflicting.status === 409,
          'A reference is meant to identify one message. Two different messages under one '
          + 'reference is a mistake in the sending software, so we report it rather than quietly '
          + 'picking one.'));
      return eventId;
    },
  },

  {
    id: 'ac5',
    tag: 'AC5',
    title: 'the full history of one message',
    blurb: 'Whatever happened, you can find out exactly what — every attempt, when, what came '
      + 'back, and what we did next.',
    async run(say) {
      const eventId = uniqueId('history');

      say.step('Setting up a message with an interesting history: two failures, then success.');
      say.curl((await api.setReceiverMode({ mode: 'FAIL_N_THEN_OK', failCount: 2, status: 503 })).curl);

      say.step(`Sending reference ${eventId} and letting it retry through to success.`);
      await api.submitEvent(messageFor(eventId));
      const event = await until(eventId, (e) => e?.delivery?.status === 'SUCCEEDED');
      say.focus(eventId);

      const read = await api.getEvent(eventId);
      say.step('Everything you are about to see came back from one single request:');
      say.curl(read.curl);

      const attempts = event?.delivery?.attempts || [];
      say.observe(`${attempts.length} attempts, in the order they happened.`,
        check(attempts.length === 3 && attempts.every((a, i) => a.attemptNumber === i + 1),
          'Oldest first, so it reads as the story it is rather than a pile of records.'));
      say.observe('Each one records what came back, how long it took, and which copy of the '
        + `service did the work (${attempts[0]?.workerId || '?'}).`,
        check(attempts.every((a) => a.workerId && a.durationMs !== null),
          'Enough to work out what went wrong without anyone reading server logs.'));
      say.note('Open "technical details" on any attempt below if you want the status codes and '
        + 'timings. They are hidden by default because most people do not need them.');
      return eventId;
    },
  },
];
