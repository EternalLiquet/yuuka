# Project Vision

## Identity

- Internal project name: **Project Yuuka**
- User-facing application name: **Yuuka**
- Suggested repository name: `project-yuuka`
- Suggested structure:
  - `backend/`
  - `mobile/`
  - `docs/`

## Product thesis

Yuuka is a paycheck-first budgeting application.

It does not primarily ask how much the user spent in a calendar month. It treats each paycheck or other incoming deposit as an ordered checklist of jobs that money must accomplish.

The user should be able to open the app and immediately understand:

- which paychecks still need attention,
- how much of each paycheck is allocated,
- how much remains unallocated,
- which entries have not been paid,
- which payments are processing,
- which payments have posted,
- and what still needs action.

## Origin of the workflow

The original system was a private Discord channel used as a handwritten ledger. One message represented one paycheck. The user listed bills, spending buckets, and leftover allocations in order.

Formatting encoded status:

- plain text: not paid,
- strikethrough: paid but not yet visible in the bank,
- bold: posted and reflected in the account balance.

Yuuka must preserve the speed, directness, and checklist feeling of that workflow while replacing manual text formatting with structured records, templates, filtering, history, and auditing.

## Product personality

Yuuka should feel calm, meticulous, private, fast, professional, and mildly strict.

It should not feel gamified, judgmental, cluttered, finance-bro styled, or like a generic analytics dashboard.

## Branding

The name references a meticulous treasurer character, but the app must not use copyrighted art, logos, character images, or game assets. The reference should remain understated.

Recommended aesthetic:

- restrained indigo/blue accent,
- clean light and dark themes,
- spacious cards,
- clear typography,
- monospaced numerals where useful,
- anime imagery
- status icons that do not rely on color alone.

## North star

When resolving ambiguity, choose the behavior that makes this question easier to answer:

> **What does this paycheck still need to accomplish?**
