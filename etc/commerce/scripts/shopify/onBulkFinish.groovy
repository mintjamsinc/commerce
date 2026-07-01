// bulk_operations/finish webhook handler. Correlates the finished bulk operation (by GID) to
// its job and hands the heavy download+reconcile off to the result route ASYNCHRONOUSLY, so
// the webhook returns fast. The lane stays RUNNING until the result route marks the job
// COMPLETED. Defensive: never throws.

import commerce.BulkJobs

def payload
try {
    def raw = (binding.hasVariable("bulkPayload") ? binding.getVariable("bulkPayload")?.toString() : null)
    payload = raw ? JSON.parse(raw) : null
} catch (Exception e) {
    log.warn("onBulkFinish: bad payload: ${e.message}")
    return
}
if (payload == null) {
    return
}

def gid = payload.admin_graphql_api_id?.toString()
def status = payload.status?.toString()?.toLowerCase()
if (gid == null) {
    log.warn("onBulkFinish: webhook has no admin_graphql_api_id")
    return
}

def job = BulkJobs.findByGid(repositorySession, gid)
if (job == null) {
    log.info("onBulkFinish: no job for bulk ${gid} - ignoring")
    return
}

if (status != "completed") {
    log.warn("onBulkFinish: bulk ${gid} finished status=${status} - marking job ${job.jobId} failed")
    BulkJobs.markFailed(repositorySession, log, job.jobId?.toString(), "bulk status ${status}")
    return
}

// Completed: dispatch the heavy result processing asynchronously.
IntegrationAPI.createMessageSender()
    .setEndpointURI("direct:commerce-shopify-bulk-result")
    .setBody("")
    .setHeader("runAs", "commerce-service-user")
    .setHeader("bulkJobId", job.jobId?.toString())
    .sendAsync()
log.info("onBulkFinish: bulk ${gid} completed - dispatched result processing for job ${job.jobId}")
