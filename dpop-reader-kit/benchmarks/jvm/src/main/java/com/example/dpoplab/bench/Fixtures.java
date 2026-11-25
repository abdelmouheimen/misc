package com.example.dpoplab.bench;

import com.example.dpoplab.DpopProofValidator;
import com.example.dpoplab.ProofFactory;
import com.example.dpoplab.ProofRequest;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Données et accès communs aux benchmarks.
 *
 * Les preuves sont produites par la fabrique de laboratoire du PoC (ProofFactory, la même que
 * l'endpoint /debug/proof) : ES256, JWK P-256 publique, claims jti/htm/htu/iat.
 *
 * Les deux méthodes privées du validateur sont appelées par MethodHandle : c'est le code de
 * production qui s'exécute, sans copie ni modification de sa visibilité dans les sources.
 */
final class Fixtures {

    static final String PUBLIC_BASE = "http://localhost:8099";
    static final String PATH = "/login/refresh";
    static final String HTU = PUBLIC_BASE + PATH;

    private Fixtures() {}

    static ProofFactory newClientKey() {
        return ProofFactory.Companion.generate();
    }

    /** Preuve fraîche et valide pour POST /login/refresh, avec le jti donné. */
    static String proof(ProofFactory key, String jti) {
        return key.build(new ProofRequest("client", "POST", HTU, "ES256", "dpop+jwt", 0L, jti,
                false, false, false));
    }

    static String newJti() {
        return UUID.randomUUID().toString();
    }

    private static final MethodHandles.Lookup LOOKUP;
    static final MethodHandle SAME_HTTP_URI;
    static final MethodHandle PURGE_EXPIRED_JTIS;
    private static final VarHandle USED_JTIS;

    static {
        try {
            LOOKUP = MethodHandles.privateLookupIn(DpopProofValidator.class, MethodHandles.lookup());
            SAME_HTTP_URI = LOOKUP.findVirtual(DpopProofValidator.class, "sameHttpUri",
                    MethodType.methodType(boolean.class, URI.class, URI.class));
            PURGE_EXPIRED_JTIS = LOOKUP.findVirtual(DpopProofValidator.class, "purgeExpiredJtis",
                    MethodType.methodType(void.class));
            USED_JTIS = LOOKUP.findVarHandle(DpopProofValidator.class, "usedJtis", ConcurrentHashMap.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("unchecked")
    static ConcurrentHashMap<String, Instant> usedJtis(DpopProofValidator v) {
        return (ConcurrentHashMap<String, Instant>) USED_JTIS.get(v);
    }

    /**
     * Remplit le store anti-rejeu avec [size] jti. Leur horodatage est placé dans le futur pour
     * qu'aucun ne soit purgé pendant la mesure : la taille reste constante, mais la purge
     * parcourt bien chaque entrée (le prédicat est évalué pour toutes).
     */
    static void prefill(DpopProofValidator v, int size) {
        ConcurrentHashMap<String, Instant> store = usedJtis(v);
        store.clear();
        Instant future = Instant.now().plusSeconds(24 * 3600);
        for (int i = 0; i < size; i++) store.put("prefill-" + i + "-" + newJti(), future);
    }
}
