package com.yuuka.backend.mcp.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;

/** Test-only token minting with caller-controlled keys, algorithms and times. */
public final class JwtMinter {
  private JwtMinter() {}

  public static RSAKey rsa(String kid) {
    try {
      return new RSAKeyGenerator(2048).keyID(kid).generate();
    } catch (JOSEException exception) {
      throw new IllegalStateException(exception);
    }
  }

  public static ECKey ec(String kid) {
    try {
      return new ECKeyGenerator(Curve.P_256).keyID(kid).generate();
    } catch (JOSEException exception) {
      throw new IllegalStateException(exception);
    }
  }

  public static JWTClaimsSet.Builder claims(
      String issuer, String audience, Instant exp, Instant nbf) {
    JWTClaimsSet.Builder builder =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject("subject-1")
            .audience(audience)
            .claim("scope", SyntheticMcpTokens.READ_SCOPE);
    if (exp != null) {
      builder.expirationTime(Date.from(exp));
    }
    if (nbf != null) {
      builder.notBeforeTime(Date.from(nbf));
    }
    return builder;
  }

  /** Signs with the key's natural asymmetric algorithm unless {@code alg} overrides it. */
  public static String sign(JWK key, JWSAlgorithm alg, JOSEObjectType type, JWTClaimsSet claims) {
    try {
      JWSSigner signer =
          key instanceof RSAKey rsa ? new RSASSASigner(rsa) : new ECDSASigner((ECKey) key);
      JWSHeader.Builder header = new JWSHeader.Builder(alg).keyID(key.getKeyID());
      if (type != null) {
        header.type(type);
      }
      SignedJWT jwt = new SignedJWT(header.build(), claims);
      jwt.sign(signer);
      return jwt.serialize();
    } catch (JOSEException exception) {
      throw new IllegalStateException(exception);
    }
  }

  public static String hs256(byte[] secret, String kid, JWTClaimsSet claims) {
    try {
      SignedJWT jwt =
          new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(kid).build(), claims);
      jwt.sign(new MACSigner(secret));
      return jwt.serialize();
    } catch (JOSEException exception) {
      throw new IllegalStateException(exception);
    }
  }

  public static String none(JWTClaimsSet claims) {
    return new PlainJWT(claims).serialize();
  }
}
