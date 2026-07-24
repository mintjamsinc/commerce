// Boot-time one-shot migration runner.
//
// Invoked once per boot by the commerce-migrate timer route (repeatCount=1, as
// the service user). Delegates to commerce.migration.Migrations: an ordered registry of
// idempotent migrations, each guarded by a permanent JCR marker
// (/content/commerce/migrations/{id}.json) so it runs exactly once per
// repository. Each migration follows migrate → verify → hard delete; on a
// verification failure no marker is written and the next boot retries.
//
// Best-effort: a failure is logged, never thrown.

import commerce.migration.Migrations
import commerce.Locks

// Task guard: the boot timer fires on every node; only the execution that
// wins the lock runs the registry, the others skip (their markers are shared
// via JCR anyway).
def __lock = Locks.tryLock(repositorySession, "commerce-migrations", 1800)
if (__lock == null) {
    log.info("runMigrations: another execution is already running the migrations - skipping")
    return
}
try {
    def report = Migrations.runAll(repositorySession, log)
    log.info("runMigrations: ran=${report.ran} skipped=${report.skipped.size()} failed=${report.failed ?: 'none'}")
} finally {
    Locks.unlock(__lock)
}
