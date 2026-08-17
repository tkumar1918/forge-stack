-- The transactional outbox.
--
-- Spring Modulith's event publication registry, which is the outbox we would otherwise hand-roll.
-- The rule it exists to enforce (plan §5): a state change and the intent to enqueue commit in the
-- same transaction, or neither does. Writing to Redis inside the transaction leaves a phantom queue
-- entry when the transaction rolls back; writing to Redis after commit, with no durable record in
-- between, loses the work silently when the process dies in the gap. There is no third option that
-- is both crash-safe and rollback-safe, so the row goes in Postgres and a relay carries it onward.
--
-- The shape below is not ours to choose: spring-modulith-events-jpa maps this table with an @Entity,
-- and Hibernate runs with ddl-auto=validate. It was generated from that entity rather than written
-- from the reference documentation — see docs/architecture-plan.md, Phase 2 step 2.1.

CREATE TABLE event_publication (
    id                     uuid PRIMARY KEY,

    -- Deliberately text rather than the varchar(255) Hibernate would have generated. A serialized
    -- event is JSON whose size is a property of the event, not of this table, and a listener id is
    -- a fully-qualified class name plus a method signature. Both are one refactor away from 255.
    -- Postgres stores varchar and text identically, so the wider type costs nothing.
    serialized_event       text        NOT NULL,
    event_type             text        NOT NULL,
    listener_id            text        NOT NULL,

    publication_date       timestamptz NOT NULL,
    completion_date        timestamptz,
    last_resubmission_date timestamptz,
    completion_attempts    integer     NOT NULL DEFAULT 0,
    status                 text,

    CONSTRAINT event_publication_status_ck CHECK (status IS NULL OR status IN (
        'PUBLISHED', 'PROCESSING', 'COMPLETED', 'FAILED', 'RESUBMITTED'))
);

-- The registry's only hot query, and the one that answers "how far behind is the relay" —
-- the metric worth alerting on, because queue depth looks healthy while the relay is stuck.
CREATE INDEX event_publication_incomplete_idx
    ON event_publication (publication_date)
    WHERE completion_date IS NULL;

-- No row-level security here, and no revoke, both deliberately and both against the habit this
-- schema has otherwise built.
--
-- RLS: an outbox row is infrastructure. It is written by whichever transaction happens to publish
-- an event and read by a relay that runs under no tenant at all, so a workspace predicate would
-- make the relay see nothing. The tenant lives *inside* the serialized event, and the listener
-- re-enters that tenant's scope before touching tenant data — see OutboxRelay.
--
-- Full DML: unlike audit_events and task_state_transitions, this table is not a record of what
-- happened. It is a work list, and a work list that cannot be updated or deleted is a leak.
-- ALTER DEFAULT PRIVILEGES already grants forgestack_app all four, so this is a note about why
-- nothing was taken away, not a grant.
