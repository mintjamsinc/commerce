// Generically normalize an inbound event that has no bespoke backend handler.
// Stores/refreshes the current state of the business entity at
// /content/commerce/{collection}/{id}.json (e.g. customers, fulfillments, carts,
// checkouts), latest update winning — the foundation other features build on.
//
// Called by the ingest core (direct:commerce-ingest) only for non-bespoke topics;
// bespoke topics (orders/products/refunds/inventory/locations) are forwarded to
// their dedicated routes instead.
//
// Inputs (route headers, ?inputs=...):
//   - event_source   : backend id
//   - event_topic    : topic / event type
//   - event_id       : unique event id
//   - ingest_payload : the raw event JSON
//
// This script intentionally lets a normalization failure propagate, so the core's
// error handler marks the event "error" and it becomes eligible for replay.

import commerce.Events

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }

def source = hv("event_source")?.toString()
def topic = hv("event_topic")?.toString()
def eventId = hv("event_id")?.toString()
def payload = hv("ingest_payload")?.toString()

Events.normalize(repositorySession, log, source, topic, eventId, payload)
