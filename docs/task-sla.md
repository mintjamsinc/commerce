# Task SLA Monitor

Keeps the workflow's human tasks (order review, refund review, inventory) from
stalling: a periodic scan escalates any open task that breaches a service-level
rule, using the same alert plumbing as the health monitor.

## How it works

```
timer:commerce-task-sla (every 15 min, as service user)
        │
        ▼
scanTaskSla.groovy ── queries open human tasks from the BPMN engine (Camunda)
        │            (order-review-flow / refund-review-flow / product-update-flow)
        ▼
commerce.TaskSla.evaluate ── applies sla.yml rules (pure logic)
        │                     ├─ escalation alert ──► commerce.Alerts ──► Notifications (#17)
        │                     └─ returns breaches ──► engine action (priority bump / group)
        ▼
commerce.TaskSla.prune ── drops cooldown state for tasks that have completed
```

`scanTaskSla.groovy` is the thin adapter that reads the live engine and applies
engine-side actions; the rule logic and debounce live in `commerce.TaskSla`
(pure, no Camunda dependency, so it is unit-testable). The scan runs as the
service user, is cheap, idempotent, and best-effort (failures are logged, never
thrown).

## Rules (`/etc/commerce/config/sla.yml`)

Evaluated per task in severity order; **at most one escalation per task per scan**
to avoid noise. Managed from **Webtop → Commerce → Tasks**.

| Rule | Fires when | Key settings |
|---|---|---|
| `overdue` | a due date is set and now is past it (+ `graceMinutes`) | `graceMinutes` |
| `unclaimed` | no assignee for longer than `minutes` | `minutes` |
| `open` | open (claimed or not) longer than `minutes` | `minutes` |

`enabled` is the master switch; each rule also has its own `enabled`.
`cooldownMinutes` debounces repeat escalations of the same task+rule (state in
`/content/commerce/tasks/sla-state.json`, pruned as tasks complete).

### Escalation action

Beyond the alert, breached tasks get an optional engine-side nudge (`escalation`):

- `priority` — raise the task's priority so it surfaces in operators' lists
  (omit / untick to leave priority untouched).
- `candidateGroup` — add a candidate group (reassign to e.g. a supervisors
  group). Blank = no reassignment.

## Alerts

Delivered as a `NotificationMessage` (title "Task SLA") to every enabled channel
in `notifications.yml`, with the task name, business context (order / product /
refund id), assignee (or "Unassigned"), age, due date, and process.

## Reading open tasks + SLA status

```
GET /bin/cms.cgi/{workspace}/content/commerce/endpoints/tasks.groovy
```

`content/commerce/endpoints/tasks.groovy` returns the open commerce tasks with a
computed `slaStatus` (`ok` / `unclaimed` / `open` / `overdue`), sorted most-urgent
first. It is read-only (never escalates) and lives outside `/content/public`, so
the CGI enforces authentication. This is the data surface for the future
Commerce dashboard.

## Shared alerting

The cooldown + channel dispatch is provided by `commerce.Alerts` (also used by the
health monitor), so SLA escalations and health alerts behave consistently and
arm their cooldown before sending — a delivery failure can never cause a storm.
