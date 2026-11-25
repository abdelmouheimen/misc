package com.example.dpoplab.bench;

import com.example.dpoplab.JwtService;
import com.example.dpoplab.TokenType;
import com.nimbusds.jwt.JWTClaimsSet;
import org.openjdk.jmh.annotations.*;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Point de comparaison : émission et vérification des jetons du PoC (JWS ES256 puis JWE
 * RSA-OAEP-256/A256GCM). Ces opérations ont lieu avec ou sans DPoP ; seule la présence du
 * claim cnf.jkt dépend de DPoP, d'où le paramètre `bound`.
 */
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g", "-XX:+UseG1GC"})
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 10, time = 5)
@Threads(1)
public class TokenBenchmark {

    @State(Scope.Thread)
    public static class Tokens {
        @Param({"true", "false"})
        boolean bound;
        JwtService jwt;
        String jkt;
        String userId = "bench-user";
        Instant sessionStart;
        String[] refreshTokens;
        int i;

        @Setup(Level.Trial)
        public void setup() {
            jwt = new JwtService("dpop-lab-backend", 300, 86400);
            jkt = bound ? Fixtures.newClientKey().jkt() : null;
            sessionStart = Instant.now();
            refreshTokens = new String[256];
            for (int n = 0; n < refreshTokens.length; n++) {
                refreshTokens[n] = jwt.issue(userId, TokenType.REFRESH, jkt, sessionStart).getValue();
            }
        }
    }

    /** Émission d'un refresh token (signature ES256 + chiffrement RSA-OAEP-256). */
    @Benchmark
    public String issueRefreshToken(Tokens t) {
        return t.jwt.issue(t.userId, TokenType.REFRESH, t.jkt, t.sessionStart).getValue();
    }

    /** Vérification d'un refresh token (déchiffrement RSA + vérification ES256 + contrôles). */
    @Benchmark
    public JWTClaimsSet verifyRefreshToken(Tokens t) {
        String token = t.refreshTokens[t.i];
        t.i = (t.i + 1) & 255;
        return t.jwt.verify(token, TokenType.REFRESH);
    }
}
