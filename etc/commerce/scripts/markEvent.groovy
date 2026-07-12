// Set the terminal status of an event-log entry.
//
// Called by the ingest core after handling: "processed" on the success path, or
// "error" (with the exception message) from the core's error handler. The event
// log's commerce:status is what the events endpoint and the auto-replay timer read
// to decide what to retry.
//
// Inputs (route headers, ?inputs=...):
//   - event_path   : the event-log resource path (from logEvent's output)
//   - event_status : "processed" | "error"
//   - event_error  : optional error detail (on the error path)
//
// Defensive: never throws.

import commerce.Events

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }

def path = hv("event_path")?.toString()
def status = hv("event_status")?.toString() ?: "processed"
def error = hv("event_error")?.toString()

Events.setStatus(repositorySession, log, path, status, error)
