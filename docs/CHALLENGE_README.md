# Caygnus Product Engineering Challenge

We are hiring a **Product Engineer / Full-Stack Developer** to build and ship products in the AI space at Caygnus.

We care less about years of experience and more about evidence: what you have shipped, the complexity or scale you have handled, and how you make engineering and product decisions. The role is available in a **remote or hybrid** working arrangement.

## Start here

Choose **one** problem and build a focused proof of your approach:

| Problem | Primary signal | Detailed brief |
| --- | --- | --- |
| Offline mobile queue | Mobile state, persistence, synchronization, and failure recovery | [View problem 1](problems/01-offline-mobile-queue/README.md) |
| Webhook retry engine | Backend reliability, delivery semantics, retries, and idempotency | [View problem 2](problems/02-webhook-retry-engine/README.md) |
| Reconnecting real-time feed | Real-time communication, reconnection, ordering, and deduplication | [View problem 3](problems/03-reconnecting-realtime-feed/README.md) |
| Observable agent loop | Agent control flow, tool execution, observability, and safety limits | [View problem 4](problems/04-observable-agent-loop/README.md) |

Read this page first, then read the complete brief for your selected problem. The problem-specific brief is the source of truth for its acceptance criteria.

## What this challenge is—and is not

This is a focused credibility exercise, not a request for a production-ready product. We want to understand how you:

- Identify the important part of a problem
- Structure software into clear responsibilities
- Choose appropriate data structures and interfaces
- Handle realistic failure and recovery cases
- Write maintainable, idiomatic code
- Test important behavior
- Explain decisions and trade-offs

We do **not** expect authentication, production infrastructure, elaborate visual design, or a long feature list. Extra scope does not compensate for an unreliable core implementation.

## Time and technology

- Submit your solution within **72 hours** of receiving or starting the challenge.
- We recommend spending approximately **6–8 hours** of active work.
- You may use **any language, framework, database, infrastructure, or model provider**.
- Explain why you selected your stack and its important trade-offs.
- An incomplete but well-reasoned submission is better than a large, overbuilt submission.

If a requirement is unclear, make a reasonable assumption, document it, and continue. We evaluate the quality of your decision—not whether you guessed an unstated preference.

## How to complete the challenge

1. Fork this repository.
2. Choose one problem from the table above.
3. Build your solution in your fork using any structure appropriate for your stack.
4. Copy [SUBMISSION_TEMPLATE.md](SUBMISSION_TEMPLATE.md) to `SUBMISSION.md` and complete every section.
5. Add focused automated tests.
6. Record the required demo video.
7. Verify that setup instructions and video permissions work for someone outside your account.
8. Submit the link to your fork.

Do not modify the problem statement to make your implementation appear compliant. If you intentionally interpret a requirement differently, explain the interpretation in `SUBMISSION.md`.

## Required submission evidence

A submission is complete only when it contains all of the following.

### 1. Runnable source code

The reviewer must be able to run the selected acceptance scenario. Never commit API keys, credentials, access tokens, private datasets, or other secrets.

### 2. Completed `SUBMISSION.md`

Use the provided [submission template](SUBMISSION_TEMPLATE.md). It asks for:

- The selected problem
- Setup and run instructions
- Architecture and data flow
- Technology choices and trade-offs
- Assumptions and limitations
- Production and scale considerations
- AI usage disclosure
- A credibility note about previously shipped work

Aim for setup instructions that a reviewer can follow within approximately 10 minutes.

### 3. Focused tests

At minimum, include:

- One test covering an important successful path
- One test covering a relevant failure or recovery path

We value meaningful tests over a high coverage percentage. Tests should not depend on paid external services to pass.

### 4. Demo video

Attach a **3–5 minute demo video** using Loom, YouTube, Google Drive, or another accessible service. Put the link near the top of `SUBMISSION.md`.

The video must show:

- The project running
- The required successful scenario
- At least one relevant failure or recovery scenario
- A brief explanation of the architecture
- One important technical decision or trade-off

A straightforward screen recording with narration is sufficient. Production-quality editing is not expected. A submission without an accessible demo video is incomplete.

### 5. Credibility note

Briefly describe one product or system you previously helped ship:

- What problem it solved
- Your personal contribution
- The scale or operational complexity involved
- One difficult engineering or product decision you made
- A public link, repository, case study, or other evidence when available

You may anonymize confidential details and use approximate figures. Scale can be demonstrated through users, traffic, concurrency, data volume, latency, reliability, cost, deployment complexity, or operational responsibility.

## Using AI tools

You may use AI tools while completing this challenge. AI usage will not reduce your score.

Disclose which tools you used and how you used them. You remain responsible for everything in your submission. We are not evaluating how much code you typed manually; we are evaluating the software you chose to submit and your understanding of it.

During review, we will consider:

- How you decomposed the problem
- The boundaries and interfaces between components
- Your data structures and data flow
- Coding patterns, consistency, and idiomatic use of your chosen stack
- Readability, naming, and maintainability
- Error handling and failure recovery
- Whether abstractions are useful rather than unnecessary
- Whether tests cover the most important behavior

You should be able to explain any part of the submission. In a follow-up discussion, we may ask you to make or describe a small change.

## How we evaluate submissions

Reviewers use the same public [review scorecard](REVIEW_SCORECARD.md) for every technology stack and problem choice.

| Area | Weight | What we look for |
| --- | ---: | --- |
| Core correctness | 25% | The selected acceptance scenarios work consistently and produce the expected outcomes. |
| Software architecture and decomposition | 25% | Responsibilities, boundaries, interfaces, and data flow are clear and appropriate. |
| Coding patterns and maintainability | 20% | The code is readable, consistent, idiomatic, and no more complicated than necessary. |
| Failure handling | 15% | Important failures are identified, observable, and handled deliberately. |
| Testing | 10% | Tests focus on valuable success, failure, and recovery behavior. |
| Communication and trade-offs | 5% | Decisions, assumptions, limitations, and alternatives are explained clearly. |

### Evaluation levels

- **Meets expectations:** The required scenarios work, important decisions are explained, and the specified failure behavior is covered.
- **Strong:** The implementation handles subtle edge cases, is easy to inspect, and demonstrates thoughtful trade-offs.
- **Exceptional:** The candidate identifies a meaningful risk we did not prescribe and addresses it simply, without unnecessary complexity.

We do not award additional points for visual polish, deployment, fashionable technology choices, or unrelated features unless they materially improve the selected capability.

## Reasons a submission may be incomplete

- The repository is inaccessible to the reviewer.
- The demo video is missing or inaccessible.
- Setup instructions are absent or cannot reasonably be followed.
- The selected problem is not identified.
- The core acceptance scenario is not demonstrated.
- Secrets or private credentials are committed.
- Large portions of submitted code cannot be explained by the candidate.

An incomplete optional feature is not a reason for rejection. Clearly label unfinished work and prioritize the required behavior.

## How to apply

Submit your repository through [the submission form](https://binary.so/mWmcQzJ), or email it to [caygnus@gmail.com](mailto:caygnus@gmail.com).

Include your resume and links to products or projects you have worked on or shipped.

We look forward to seeing how you think and build.