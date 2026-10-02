---
phase: 8
slug: phase-8-retrospective
title: Retrospective and portfolio
duration: 1.5 h
start_tag: phase-7-end
end_tag: phase-8-end
---
# Phase 8 · Retrospective and portfolio

## 1. Why it matters
A recruiter spends about a minute on your repository. "Another e-commerce API" is forgettable; a README that
tells the story (the problem, the security design, the trade-offs, the evidence, what you would do next)
is proof that you can design, test, ship and operate software, and that you use AI responsibly. The
retrospective is for you: it turns nine phases of experience into lessons you can explain in an interview.

## 2. Objectives
- Rewrite the README top section as a **case study** with real numbers from your project.
- Write a short **retrospective** (what went well, what didn't, what you'd change).
- Summarise your **AI_LOG** into three honest bullet points.
- Prepare a 2-minute walkthrough and answers to the security questions interviewers ask about auth APIs.

## 3. Step-by-step instructions

### Step 8.1 · Collect your evidence
```bash
git log --oneline | wc -l                       # commits
git tag -l 'v*'                                  # releases
make coverage                                    # coverage % per track
scripts/smoke-test.sh | tail -1                  # e.g. "57 passed, 0 failed, 0 skipped"
curl -s localhost:8080/metrics | grep _total     # counters after a demo session
```
Also grab: a screenshot of the CI run graph, the GHCR package page, your game-day numbers and
(optionally) your live URL.

### Step 8.2 · README case study
At the top of `README.md`, before the setup instructions, add a section with:
1. **One-line pitch** and a short `curl` session (register → cart → checkout twice with one key → signed webhook → PAID).
2. **Problem → solution** in three sentences.
3. **Architecture** (link the Mermaid diagrams) and the **three decisions** you are proudest of, linking
   ADRs (for example money as integer paise, the guarded stock decrement, idempotent checkout, the outbox).
4. **Security and correctness evidence:** which threats from Phase 1 are covered by which tests (forged webhooks, overselling, double checkout, other people's orders
   are `404`, `role` mass assignment ignored, algorithm pinned).
5. **Quality evidence:** tests, coverage, CI badge, smoke test.
6. **Operations:** health, metrics, request IDs, the game day and its post-mortem.
7. **How I used AI**, with honest examples of accepted and rejected suggestions.
8. **What I'd do next** (a scheduler for the expiry job, a real payment provider, rate limiting on login, OpenTelemetry, a small front end…).

### Step 8.3 · Retrospective (`docs/retrospective.md`)
Use *Start / Stop / Continue* or *What went well / What didn't / What I'd change*. Include one
technical lesson (e.g. a fallback is only graceful if the failure is fast), one process lesson
(test-first with a pending list) and one AI lesson (an assistant confidently produced insecure or
outdated code and a test or the docs caught it).

### Step 8.4 · Practise the walkthrough
Record yourself (2 minutes): problem, architecture diagram, one interesting piece of code (the
guarded stock decrement or the webhook handler are good ones), the test that proves it, the CI run,
what you'd do next. Then answer out loud:
- *Why integer paise?* · *What stops two customers buying the last unit?* · *What happens when the client retries a checkout?*
- *How do you know a webhook really came from the provider?* · *What if it arrives twice, or after a cancel?*
- *Why an outbox instead of calling the broker after commit?* · *What happens when the LLM hangs?* · *Where did AI help, and where did it mislead you?*

### Step 8.5 · Share it
Pin the repository on your GitHub profile, add the topics `rest-api`, `jwt`, `<your-language>`,
`docker`, `github-actions`, and post the case study (aicancode.org *Build in public*, LinkedIn).

## 4. AI-assist prompts

### Prompt 1: Draft the case study from evidence
```text
Write a README "case study" section (max 400 words) for my e-commerce backend project from these facts
ONLY: <paste your evidence: test counts, coverage, ADR titles, threats and the tests that cover them,
metrics, post-mortem summary, AI_LOG highlights>. Use short headings. Do not invent numbers, features
or users. Mark anything you are unsure about with [CHECK].
```
**Verify the output by:** checking every number and claim against your evidence list and deleting
anything you cannot show in the repository. If a claim has no link to a file, test or run, cut it.
Watch for security over-claims ("fully secure", "OWASP compliant"): replace them with the specific
threats you handled.

### Prompt 2: Mock security interview
```text
Act as a backend interviewer who cares about security. Ask me 8 questions about this project, one at a
time, from easy to hard, based on this README, the threat list and the ADRs: <paste>. After each answer,
tell me what a strong answer would add. Start now with question 1.
```
**Verify the output by:** checking its "strong answers" against your ADRs, the OWASP cheat sheets
(JWT, password storage, authorization, transaction authorization) and the RFCs (RFC 9457, RFC 2104 on HMAC). An
interviewer persona can still be wrong about details.

## 5. Deliverables
- [ ] README case study with real evidence and links.
- [ ] `docs/retrospective.md`.
- [ ] AI usage summary (three bullets) in the README, backed by `AI_LOG.md`.
- [ ] A recorded 2-minute walkthrough (optional, but recommended).

## 6. Self-check quiz

### Q1. Which README claim is strongest?
- [ ] "Enterprise-grade, fully secure authentication."
- [ ] "Uses JWT best practices."
- [x] "Eight parallel checkouts for 3 units create exactly 3 orders and leave stock at 0, proven by an integration test (link) against real PostgreSQL."
- [ ] "Built with AI."
> **Answer:** Specific, verifiable, linked. Reviewers trust evidence, not adjectives.

### Q2. What belongs in "What I'd do next"?
- [x] Concrete, prioritised improvements with a reason (e.g. login rate limiting, because `shopflow_logins_failed_total` is the only defence against credential stuffing today)
- [ ] Everything that is missing
- [ ] Apologies for unfinished work
- [ ] Nothing, it signals weakness
> **Answer:** Knowing the next steps and why shows judgement, and gives the interviewer something to discuss.

### Q3. How should you describe AI use in your portfolio?
- [ ] Hide it
- [ ] Claim the AI wrote everything
- [x] Honestly, with examples of what you verified, changed and rejected
- [ ] Only mention it if asked
> **Answer:** Responsible AI use is a skill employers look for. Your AI_LOG is the evidence.

### Q4. An interviewer asks "What happens when the LLM hangs?" Which answer is best?
- [ ] "The LLM is a managed service, so it does not hang."
- [ ] "Search fails until it comes back."
- [x] "Search assist falls back to the offline rules and checkout never calls the LLM; after three timeouts a circuit breaker skips the LLM for 30 s, which cut the average from 5 s to 1.5 s in a game day."
- [ ] "I would switch to a bigger model."
> **Answer:** Describe the designed behaviour, how you proved it (tests + game day), and what you learned
> (correct but slow is still an incident, see the reference post-mortem).

### Q5. Which retrospective item is most useful?
- [ ] "Security was hard."
- [x] "Our LLM failure test used a refused connection; next time we also test a hanging endpoint and assert a latency budget."
- [ ] "Everything went well."
- [ ] "The AI made mistakes."
> **Answer:** A good retrospective item names a specific cause and a concrete, testable change.

## 7. What you learned
- Turning a project into a case study backed by evidence, including security evidence.
- Running a retrospective and extracting transferable lessons.
- Communicating trade-offs and AI usage clearly and honestly.
- The full lifecycle: requirements and threats → design → implementation → tests → CI/CD → operations → reflection.

## 8. Catch-up git commands
```bash
git fetch upstream --tags
git show phase-8-end:README.md | less           # reference case study
git show phase-8-end:docs/retrospective.md
git diff phase-7-end phase-8-end --stat         # docs only
```
