// Persist an inbound integration event to the durable event log (category A).
//
// Called by the source-agnostic ingest core (direct:commerce-ingest) on EVERY
// event, from any backend, before the event is handled. The event log keeps the
// raw payload so the event can be replayed (#4) and audited, and is the normalized
// record that makes every topic a first-class business event (#1).
//
// Inputs (route headers, ?inputs=...):
//   - event_source   : backend id (e.g. "shopify")
//   - event_topic    : topic / event type (e.g. "orders/paid", "customers/create")
//   - event_id       : unique event id (the webhook id), used as the log key
//   - received_at    : ISO receipt timestamp
//   - ingest_payload : the raw event JSON
//
// Output (?outputs=event_path): the event-log resource path, so the core can mark
// its terminal status without re-finding it.
//
// Defensive: a logging failure must never break ingestion.

import commerce.Events

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }

def source = hv("event_source")?.toString()
def topic = hv("event_topic")?.toString()
def eventId = hv("event_id")?.toString()
def receivedAt = hv("received_at")?.toString()
def payload = hv("ingest_payload")?.toString()

def path = Events.logEvent(repositorySession, log, source, topic, eventId, receivedAt, payload)
context.setAttribute("event_path", path)
