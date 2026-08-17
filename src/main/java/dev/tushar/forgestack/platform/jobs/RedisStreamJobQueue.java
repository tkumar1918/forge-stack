package dev.tushar.forgestack.platform.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis Streams behind {@link JobQueue}.
 *
 * <p>Streams rather than lists or pub/sub because of the pending-entries list: a claimed but
 * unacknowledged message stays visible, so "which worker has been holding what, for how long" is a
 * question the transport can answer. For work measured in hours that is the difference between an
 * observable system and a silent one. {@code BLPOP} drops in-flight messages when a worker dies and
 * pub/sub does not deliver to anyone who was not listening.
 *
 * <p><strong>Everything here is disposable.</strong> Losing this Redis loses queue position and
 * nothing else: recovery does not come from the pending-entries list, it comes from the lease
 * reconciler scanning Postgres. That is deliberate — a recovery path that depended on Redis would
 * make Redis load-bearing, and the whole point of §5 is that it is not.
 */
@Component
class RedisStreamJobQueue implements JobQueue {

    /**
     * A ceiling, not a retention policy. Acknowledging deletes the entry, so a healthy stream stays
     * near empty and this bound is never reached; it exists so that a consumer group that stops
     * acknowledging costs bounded memory instead of taking the node down.
     */
    private static final long MAX_STREAM_LENGTH = 100_000;

    private static final String STREAM_PREFIX = "forge:q:";
    private static final String GROUP_PREFIX = "forge:cg:";

    private static final String FIELD_ID = "id";
    private static final String FIELD_KIND = "kind";
    private static final String FIELD_WORKSPACE = "workspace";
    private static final String FIELD_RESOURCE = "resource";
    private static final String FIELD_ENQUEUED_AT = "enqueuedAt";

    private final StringRedisTemplate redis;

    /**
     * Which groups this process has already created, so the common case costs no round trip.
     *
     * <p>Only a cache. A {@code FLUSHALL} destroys the group without telling us, so the read path
     * still has to recover from "NOGROUP" rather than trust this.
     */
    private final Set<String> knownGroups = ConcurrentHashMap.newKeySet();

    RedisStreamJobQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void enqueue(JobMessage job) {
        String stream = streamKey(job.kind());
        streams().add(stream, toFields(job));
        streams().trim(stream, MAX_STREAM_LENGTH, true);
    }

    @Override
    public List<DeliveredJob> poll(String kind, String consumer, int count, Duration block) {
        ensureGroup(kind);
        try {
            return read(kind, consumer, count, block);
        } catch (DataAccessException e) {
            // The group vanished under us — a FLUSHALL, or a Redis that was restarted empty.
            // Recreating it and reading again is correct rather than merely convenient: the durable
            // record of what is owed lives in Postgres, so an empty stream is a real, valid state.
            if (!mentionsMissingGroup(e)) {
                throw e;
            }
            knownGroups.remove(kind);
            ensureGroup(kind);
            return read(kind, consumer, count, block);
        }
    }

    @Override
    public void acknowledge(DeliveredJob delivered) {
        String stream = streamKey(delivered.job().kind());
        RecordId id = RecordId.of(delivered.deliveryId());
        streams().acknowledge(stream, groupKey(delivered.job().kind()), id);
        // Acknowledging alone leaves the entry in the stream forever, which matters here because a
        // group recreated after a flush starts at offset 0 and would replay everything ever sent.
        // Deleting on acknowledgement makes that replay cover exactly the work still owed.
        streams().delete(stream, id);
    }

    @Override
    public long depth(String kind) {
        Long size = streams().size(streamKey(kind));
        return size == null ? 0 : size;
    }

    // ---------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<DeliveredJob> read(String kind, String consumer, int count, Duration block) {
        StreamReadOptions options = StreamReadOptions.empty().count(count);
        // Redis reads BLOCK 0 as "wait forever", so a zero duration must mean "do not block" here
        // rather than being passed through. A caller asking for no wait and getting an unkillable
        // one is the kind of mistake that only shows up as a thread that never comes back.
        if (block != null && !block.isZero() && !block.isNegative()) {
            options = options.block(block);
        }
        List<MapRecord<String, String, String>> records = streams()
                .read(
                        Consumer.from(groupKey(kind), consumer),
                        options,
                        StreamOffset.create(streamKey(kind), ReadOffset.lastConsumed()));
        return records == null ? List.of() : records.stream().map(this::toDelivered).toList();
    }

    private void ensureGroup(String kind) {
        if (!knownGroups.add(kind)) {
            return;
        }
        try {
            // From 0, not from $. A group created at $ ignores everything already on the stream,
            // which after a flush-and-refill is precisely the work the reconciler just recovered.
            streams().createGroup(streamKey(kind), ReadOffset.from("0"), groupKey(kind));
        } catch (DataAccessException e) {
            if (!mentionsExistingGroup(e)) {
                knownGroups.remove(kind);
                throw e;
            }
        }
    }

    private Map<String, String> toFields(JobMessage job) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(FIELD_ID, job.id().toString());
        fields.put(FIELD_KIND, job.kind());
        fields.put(FIELD_WORKSPACE, job.workspaceId().toString());
        fields.put(FIELD_RESOURCE, job.resourceId() == null ? "" : job.resourceId().toString());
        fields.put(FIELD_ENQUEUED_AT, job.enqueuedAt().toString());
        return fields;
    }

    private DeliveredJob toDelivered(MapRecord<String, String, String> record) {
        Map<String, String> fields = record.getValue();
        String resource = fields.get(FIELD_RESOURCE);
        JobMessage job = new JobMessage(
                UUID.fromString(fields.get(FIELD_ID)),
                fields.get(FIELD_KIND),
                UUID.fromString(fields.get(FIELD_WORKSPACE)),
                resource == null || resource.isEmpty() ? null : UUID.fromString(resource),
                Instant.parse(fields.get(FIELD_ENQUEUED_AT)));
        return new DeliveredJob(record.getId().getValue(), job);
    }

    private StreamOperations<String, String, String> streams() {
        return redis.opsForStream();
    }

    private static String streamKey(String kind) {
        return STREAM_PREFIX + kind;
    }

    private static String groupKey(String kind) {
        return GROUP_PREFIX + kind;
    }

    // Redis reports both of these as plain error strings, which Spring wraps without a code worth
    // switching on. Matching the text is unpleasant and it is what the protocol offers.
    private static boolean mentionsMissingGroup(DataAccessException e) {
        return messageOf(e).contains("NOGROUP");
    }

    private static boolean mentionsExistingGroup(DataAccessException e) {
        return messageOf(e).contains("BUSYGROUP");
    }

    private static String messageOf(Throwable e) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null) {
                text.append(cause.getMessage()).append('\n');
            }
        }
        return text.toString();
    }
}
