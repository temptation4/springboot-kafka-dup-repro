Reproduce lost-ACK → automatic-retry → duplicate (exact steps)

Prerequisites (important)

App running at http://localhost:8081.

spring.kafka.producer.properties must include (temporary test values):

acks: all
retries: 200
max.in.flight.requests.per.connection: 1
request.timeout.ms: 1000      # short so producer times out quickly
delivery.timeout.ms: 120000
enable.idempotence: false    # MUST be false to allow duplicates on retry


Confirm you restarted the app after changing the config.

Single-shot reproduce & verify (copy-paste these, step-by-step)

Important: call /send only once in step 3. The duplicate must come from the producer retrying automatically, not from you calling /send twice.

# 1) block upstream (drop ACKs)
curl -s -X POST "http://localhost:8081/proxy/block" && echo blocked

# 2) confirm proxy is blocked
curl -s http://localhost:8081/status | jq .
# expect: "proxyBlocked": true

# 3) send exactly one message (do NOT call /send again)
KEY="dup-auto-$(date +%s%3N)"; echo "KEY=$KEY"
curl -s -X POST "http://localhost:8081/send?key=${KEY}" -w "\nHTTP_CODE:%{http_code}\n"

# 4) wait longer than request.timeout.ms to force producer timeout & retries
#    with request.timeout.ms=1000 we recommend waiting ~10-12s
sleep 12

# 5) unblock ACKs (allow retries/ACKs to flow)
curl -s -X POST "http://localhost:8081/proxy/unblock" && echo unblocked

# 6) verify the broker contains duplicate entries for the key
#    Replace <LEADER_CONTAINER_ID> with your Kafka leader container id (example: 6d24bbdb304a)
docker exec -i <LEADER_CONTAINER_ID> /usr/bin/kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic order.created --from-beginning --max-messages 1000 \
  --property print.key=true --property print.offset=true --property key.separator='|' \
  | grep "${KEY}" -n


Expected result: you should see two (or more) lines with the same key but different offsets, e.g.

16:Offset:15|dup-auto-1759...|order-id=dup-auto-1759...|ts=...
17:Offset:16|dup-auto-1759...|order-id=dup-auto-1759...|ts=...


This proves the first write was persisted by the broker while ACKs were blocked, and the producer retried (causing a second persisted message with the same key).

Troubleshooting

If you don’t see Send failed in app.log while blocked: proxy did not block upstream properly. Check curl http://localhost:8081/status again and inspect app.log for Kafka NetworkClient warnings while proxy is blocked.

If you never see duplicates:

Lower request.timeout.ms (e.g., 800 → restart app).

Increase the sleep (step 4) so the producer has time to time out.

Confirm enable.idempotence: false.

If kafka-console-consumer is not found at /usr/bin/kafka-console-consumer in your container, run docker exec -it <cid> bash to find the full path and replace the command accordingly.
