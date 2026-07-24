// CMS CONSUMER lane (timer-driven, cluster-guarded): dispatch the heavy download+reconcile for
// jobs whose Shopify bulk has COMPLETED (status READY). This lane is SEPARATE from the Shopify
// producer lane (runBulkLane) and holds its OWN cluster lock, so ingest can proceed IN PARALLEL
// for jobs whose data DOMAINS are DISJOINT and serializes only when domains OVERLAP: a domain
// currently PROCESSING blocks another READY job of the same domain until it finishes.
//
// Today (single "inventory" domain) this behaves as a safe serial lane; when future backfills
// (products/orders/customers) are added it auto-pipelines disjoint domains. Multiple disjoint
// READY jobs are dispatched per tick. Defensive: never throws; a bookkeeping failure on one job
// must not stop the others.

import commerce.BulkJobs
import commerce.Locks

def lock = Locks.tryLock(repositorySession, "commerce-shopify-bulk-cms-lane", 60)
if (lock == null) {
    return
}
try {
    def readyJobs = BulkJobs.ready(repositorySession)
    if (readyJobs == null || readyJobs.isEmpty()) {
        return
    }
    // Domains already undergoing ingest (from earlier ticks) block overlapping READY jobs.
    def busyDomains = BulkJobs.domainsInStatuses(repositorySession, ["PROCESSING"])

    // Cap the TOTAL number of concurrent CMS ingests (across disjoint domains) so a burst of
    // multi-domain backfills cannot overload the CMS. 0 = unlimited (domain-disjointness only).
    // `active` counts PROCESSING jobs from earlier ticks and grows as we claim more this tick.
    def recCfg = readYaml("/etc/commerce/config/reconcile.yml")
    int maxConcurrent = intOr(recCfg?.maxConcurrentIngest, 3)
    int active = BulkJobs.processing(repositorySession).size()

    readyJobs.sort { it.jobId?.toString() }.each { job ->
        try {
            if (maxConcurrent > 0 && active >= maxConcurrent) {
                return  // concurrent-ingest cap reached - remaining READY jobs wait for a free slot
            }
            def domains = BulkJobs.domainsOf(job)
            if (BulkJobs.overlaps(domains, busyDomains)) {
                return  // a job of an overlapping domain is still ingesting - wait this tick
            }
            def jid = job.jobId?.toString()
            // Claim PROCESSING BEFORE dispatch: this is how the lane reserves a domain-safe
            // ingest slot (also stops a concurrent tick / the watchdog re-dispatching it).
            // markProcessing is a GUARDED transition — it returns true ONLY if the READY->PROCESSING
            // claim actually persisted. GATE the dispatch on it: if the claim did NOT persist (the
            // job is no longer READY because another tick/node already grabbed it, or the commit
            // failed), do NOT dispatch — leave the domain unclaimed so the next tick can retry.
            if (BulkJobs.markProcessing(repositorySession, log, jid)) {
                IntegrationAPI.createMessageSender()
                    .setEndpointURI("direct:commerce-shopify-bulk-result")
                    .setBody("")
                    .setHeader("runAs", "commerce-service-user")
                    .setHeader("bulkJobId", jid)
                    .setHeader("bulkJobType", job.type?.toString())
                    .sendAsync()
                log.info("runBulkCmsLane: dispatched ingest for job ${jid} domains=${domains}")
                active++
                // Re-read the PROCESSING domain union from the just-committed state so the domains
                // we claimed this tick (including a wildcard job's) block any later overlapping
                // READY job. (Equivalent to adding job.domains to busyDomains, but delegates the
                // union/wildcard semantics to BulkJobs so they stay consistent with overlaps().)
                busyDomains = BulkJobs.domainsInStatuses(repositorySession, ["PROCESSING"])
            } else {
                log.info("runBulkCmsLane: markProcessing claim for job ${jid} did not persist (not READY / commit failed) - skipping this tick")
            }
        } catch (Exception e) {
            log.warn("runBulkCmsLane: job ${job?.jobId}: ${e.message}")
        }
    }
} catch (Exception e) {
    log.warn("runBulkCmsLane: ${e.message}")
} finally {
    Locks.unlock(lock)
}

def readYaml(String path) {
    try {
        def res = repositorySession.getResource(path)
        if (res != null && res.exists()) return YAML.parse(res)
    } catch (Exception e) {}
    return null
}

int intOr(v, int dflt) {
    if (v == null) return dflt
    try { return v.toString().trim() as int } catch (Exception e) { return dflt }
}
