# Fragment reference index and GC

`file-change-refs` (audit consumer `5`) runs on writable workers and indexes
retained file history. It does not mark fragments unless
`PENPOT_FRAGMENTS_GC_ENABLED=true`. The existing file GC, physical purge and
offload do not change.

## Deploy the index

1. Check that consumer `5` has not been assigned to a different consumer.
   Do not reset another consumer's cursor. Stop old runners on the shared
   queue before starting the first worker that can enqueue the new jobs.
   Ensure old web writers are also gone before evaluating the marking gates.
   If a test environment ran the earlier two-job implementation, drain
   those jobs on that build before switching: the index job now accepts
   only `file-id`, and there is no separate repair job.
2. Apply `0160-add-file-change-refs`. It creates an empty partitioned table
   and a foreign key to `file_change`; there is no global backfill. The
   foreign key takes a lock on the referenced table. If the normal migration
   window cannot take that lock, plan a maintenance window instead.
3. Start the new workers with marking off. The web process writes full audit
   events regardless of the Nexus and admin-console flags. Watch the size of
   `audit_log`, consumer lag and `index-file-change-refs` failures. Stopping
   the consumer does not remove its cursor: unread full events can remain in
   the buffer until it comes back. Do not delete the cursor to catch up.

The ordinary replay processes events still in the buffer. It does not
reconstruct purged events. The existing RPC events for an effective save
or manual snapshot enqueue one pending `index-file-change-refs` job per
file. Each run fills the missing rows in pages of 32, including other
retained history that did not emit an event. A running job does not stop
a later event from enqueuing new work; a file with no trigger is not
backfilled.

Only effective saves trigger fragment GC. If a GC run finds missing rows,
it does not mark anything: it enqueues the same index job and uses the
worker's retry budget with a one-minute base delay. It checks coverage again
on each attempt. If retries run out before indexing finishes, a later
save can trigger GC again. An unreadable snapshot stays uncovered, but
does not stop the index job from filling other rows before it requests a
retry. Check the failed job and source data rather than filling in an
empty array.

## Before enabling marking

- Verify that the deployed writers retain the required revisions and snapshots
  (both metadata and payload), including automatic snapshots, restore backups
  and legacy rows already dated for deletion. The index does not change retention.
- Verify the locked read fix is deployed on all writers. Pause and drain
  marking before administrative rewrites of main or history that bypass the
  normal writer protocol. Files without a live canonical `file_data` main
  are not eligible.
- Keep tiered offload disabled throughout the fleet and drain any pending or
  running `offload-file-data` work. Do not enable marking while a legacy
  producer can queue more offload jobs. The job gate also checks active
  offload rows for its tenant before the GC can acquire a file lock.
- Test the bundle followed by a fragment GET without a cached response.
  Measure index job latency and database load during replay and on files
  with long histories: the page size bounds the missing rows returned, not
  the rows PostgreSQL reads to find them.
  Measure `update-file` p95/p99 and the time spent waiting for locks under
  concurrent editing on representative long histories. Compare against
  the same workload with the legacy GC. Agree on the latency budget before
  enabling the job. Do not shorten `PENPOT_FILE_CLEAN_DELAY` below the
  existing default of two days without testing that shorter window.

## Stop or roll back

Turn `PENPOT_FRAGMENTS_GC_ENABLED` off on the consumer and all runners.
Queued marking jobs then exit before locking a file. Wait for already
running marking to finish. Keep the new job definitions running until the
new jobs are drained or isolated before rolling back to older workers.
Disabling marking neither reverses existing marks nor restores purged
payloads. The index can keep running while marking is off.
