package com.example.dpoplab.bench;

import com.example.dpoplab.DpopProofValidator;
import com.example.dpoplab.DpopResult;
import com.example.dpoplab.ProofFactory;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.net.URI;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Coût serveur d'une preuve DPoP, étape par étape puis de bout en bout.
 *
 * Toutes les entrées viennent de champs d'état non finals et changent à chaque appel
 * (rotation dans un pool), et tous les résultats sont consommés (retour ou Blackhole) :
 * ni élimination de code mort, ni repliement de constantes.
 */
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g", "-XX:+UseG1GC"})
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 10, time = 5)
@Threads(1)
public class DpopValidationBenchmark {

    /** Pool de preuves distinctes (clés et jti différents), préparé hors mesure. */
    @State(Scope.Thread)
    public static class Proofs {
        static final int SIZE = 1024;
        String[] raw;
        SignedJWT[] parsed;
        ECKey[] jwks;
        ECPublicKey[] publicKeys;
        JWTClaimsSet[] claims;
        int i;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            raw = new String[SIZE];
            parsed = new SignedJWT[SIZE];
            jwks = new ECKey[SIZE];
            publicKeys = new ECPublicKey[SIZE];
            claims = new JWTClaimsSet[SIZE];
            ProofFactory[] keys = new ProofFactory[16];
            for (int k = 0; k < keys.length; k++) keys[k] = Fixtures.newClientKey();
            for (int n = 0; n < SIZE; n++) {
                raw[n] = Fixtures.proof(keys[n % keys.length], Fixtures.newJti());
                parsed[n] = SignedJWT.parse(raw[n]);
                jwks[n] = (ECKey) parsed[n].getHeader().getJWK();
                publicKeys[n] = jwks[n].toECPublicKey();
                claims[n] = parsed[n].getJWTClaimsSet();
                if (!parsed[n].verify(new ECDSAVerifier(publicKeys[n]))) throw new IllegalStateException("bad proof");
            }
        }

        int next() {
            int n = i;
            i = (n + 1) & (SIZE - 1);
            return n;
        }
    }

    /** Validateur de production, pour l'appel par MethodHandle de sa comparaison d'URI. */
    @State(Scope.Thread)
    public static class Validator {
        DpopProofValidator validator;
        String publicBase = Fixtures.PUBLIC_BASE;
        String path = Fixtures.PATH;
        String method = "POST";

        @Setup(Level.Trial)
        public void setup() {
            validator = new DpopProofValidator(Fixtures.PUBLIC_BASE);
        }
    }

    // ---- 1. Parsing (JWS compact + en-tête JSON, dont la JWK embarquée) -------------------

    @Benchmark
    public SignedJWT parse(Proofs p) throws Exception {
        return SignedJWT.parse(p.raw[p.next()]);
    }

    // ---- 2. Reconstruction de la clé publique à partir de la JWK ---------------------------

    @Benchmark
    public ECPublicKey publicKeyFromJwk(Proofs p) throws Exception {
        return p.jwks[p.next()].toECPublicKey();
    }

    // ---- 3. Vérification ES256 (construction du vérificateur incluse, comme dans le PoC) ---

    @Benchmark
    public boolean verifySignature(Proofs p) throws Exception {
        int n = p.next();
        return p.parsed[n].verify(new ECDSAVerifier(p.publicKeys[n]));
    }

    // ---- 4. Thumbprint JWK (RFC 7638), sérialisé comme dans le validateur -------------------

    @Benchmark
    public String jwkThumbprint(Proofs p) throws Exception {
        return p.jwks[p.next()].computeThumbprint().toString();
    }

    // ---- 5. Claims : extraction depuis la charge utile, puis contrôles htm, htu et iat ----

    @Benchmark
    public JWTClaimsSet claimsExtract(Proofs p) throws Exception {
        return JWTClaimsSet.parse(p.parsed[p.next()].getPayload().toJSONObject());
    }

    /**
     * Contrôles 4 à 6 du validateur sur des claims déjà extraits. La comparaison d'URI est celle
     * du PoC (sameHttpUri, par MethodHandle) ; htm et iat sont deux comparaisons triviales,
     * reprises à l'identique.
     */
    @Benchmark
    public boolean claimsChecks(Proofs p, Validator v) throws Throwable {
        JWTClaimsSet c = p.claims[p.next()];
        String htm = c.getStringClaim("htm");
        if (!v.method.equalsIgnoreCase(htm)) return false;
        String htu = c.getStringClaim("htu");
        URI signed = new URI(htu).normalize();
        URI expected = new URI(v.publicBase + v.path).normalize();
        if (!(boolean) Fixtures.SAME_HTTP_URI.invoke(v.validator, signed, expected)) return false;
        long skew = Math.abs(Instant.now().getEpochSecond() - c.getIssueTime().toInstant().getEpochSecond());
        // Le pool a été généré au début de l'essai : iat vieillit, mais le calcul est identique.
        return skew <= 60L;
    }

    // ---- 6. Anti-rejeu : purge + insertion, à taille de store constante -------------------

    @State(Scope.Thread)
    public static class ReplayStore {
        /** Taille du store ≈ débit de preuves acceptées × 120 s (TTL des jti dans le PoC). */
        @Param({"0", "1200", "12000", "60000"})
        int storeSize;
        DpopProofValidator validator;
        ConcurrentHashMap<String, Instant> used;
        String[] jtis;
        int i;

        @Setup(Level.Trial)
        public void setup() {
            validator = new DpopProofValidator(Fixtures.PUBLIC_BASE);
            Fixtures.prefill(validator, storeSize);
            used = Fixtures.usedJtis(validator);
            jtis = new String[4096];
            for (int n = 0; n < jtis.length; n++) jtis[n] = Fixtures.newJti();
        }

        @TearDown(Level.Iteration)
        public void check() {
            if (used.size() != storeSize) throw new IllegalStateException("store size drifted: " + used.size());
        }
    }

    /**
     * Ce que fait le validateur pour l'anti-rejeu (purgeExpiredJtis puis putIfAbsent), suivi du
     * retrait du jti inséré pour garder la taille constante. Le retrait (une opération de
     * ConcurrentHashMap) est inclus dans la mesure.
     */
    @Benchmark
    public boolean replayDetection(ReplayStore s) throws Throwable {
        String jti = s.jtis[s.i];
        s.i = (s.i + 1) & 4095;
        Fixtures.PURGE_EXPIRED_JTIS.invoke(s.validator);
        boolean fresh = s.used.putIfAbsent(jti, Instant.now()) == null;
        s.used.remove(jti);
        return fresh;
    }

    // ---- 7. Validation complète par le vrai DpopProofValidator ----------------------------

    @State(Scope.Thread)
    public static class FreshProofs {
        @Param({"0", "1200", "12000", "60000"})
        int storeSize;
        /** Doit dépasser le nombre d'opérations d'une itération (vérifié à la fin de chacune). */
        @Param({"20000"})
        int poolSize;

        DpopProofValidator validator;
        ConcurrentHashMap<String, Instant> used;
        ProofFactory[] keys;
        String[] proofs;
        String[] jtis;
        int i;
        long invalid;

        @Setup(Level.Trial)
        public void trial() {
            validator = new DpopProofValidator(Fixtures.PUBLIC_BASE);
            Fixtures.prefill(validator, storeSize);
            used = Fixtures.usedJtis(validator);
            keys = new ProofFactory[64];
            for (int k = 0; k < keys.length; k++) keys[k] = Fixtures.newClientKey();
            proofs = new String[poolSize];
            jtis = new String[poolSize];
        }

        /**
         * Preuves neuves (iat = maintenant, jti uniques) avant chaque itération, hors mesure.
         * Elles sont produites par un pool de threads dédié : le profileur GC de JMH compte les
         * allocations du seul thread mesuré, qui n'alloue donc ici que pour la validation.
         * Un GC explicite libère ensuite les déchets de génération avant la mesure.
         */
        @Setup(Level.Iteration)
        public void iteration() throws Exception {
            java.util.concurrent.ForkJoinPool gen = new java.util.concurrent.ForkJoinPool(4);
            try {
                gen.submit(() -> java.util.stream.IntStream.range(0, poolSize).parallel().forEach(n -> {
                    jtis[n] = Fixtures.newJti();
                    proofs[n] = Fixtures.proof(keys[n % keys.length], jtis[n]);
                })).get();
            } finally {
                gen.shutdown();
            }
            i = 0;
            invalid = 0;
            System.gc();
        }

        /** Garde-fou : on ne doit jamais avoir mesuré le chemin de rejet. */
        @TearDown(Level.Iteration)
        public void check() {
            if (i >= poolSize) throw new IllegalStateException("proof pool exhausted: raise poolSize");
            if (invalid != 0) throw new IllegalStateException(invalid + " proofs rejected during measurement");
            if (used.size() != storeSize) throw new IllegalStateException("store size drifted: " + used.size());
        }
    }

    @Benchmark
    public void fullValidation(FreshProofs s, Blackhole bh) {
        int n = s.i++;
        DpopResult r = s.validator.validate(s.proofs[n], "POST", Fixtures.PATH);
        if (!(r instanceof DpopResult.Valid)) s.invalid++;
        s.used.remove(s.jtis[n]); // taille du store constante (retrait inclus dans la mesure)
        bh.consume(r);
    }
}
