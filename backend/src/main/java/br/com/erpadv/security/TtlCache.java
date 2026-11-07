package br.com.erpadv.security;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Cache em memória com expiração por entrada (dados lidos a cada requisição autenticada). */
public class TtlCache<K, V> {

    private record Entry<V>(Optional<V> value, long expiresAt) {
    }

    private final long ttlMillis;
    private final int maxEntries;
    private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();

    public TtlCache(Duration ttl, int maxEntries) {
        this.ttlMillis = Math.max(1000, ttl.toMillis());
        this.maxEntries = maxEntries;
    }

    public Optional<V> get(K key, Function<K, Optional<V>> loader) {
        long now = System.currentTimeMillis();
        Entry<V> entry = entries.get(key);
        if (entry != null && entry.expiresAt() > now) {
            return entry.value();
        }
        Optional<V> loaded = loader.apply(key);
        if (entries.size() >= maxEntries) {
            entries.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
            if (entries.size() >= maxEntries) {
                entries.clear();
            }
        }
        entries.put(key, new Entry<>(loaded, now + ttlMillis));
        return loaded;
    }

    public void evict(K key) {
        entries.remove(key);
    }
}
