package io.matedata.identity;

/** Produces a keyed public cache token without disclosing the internal policy fingerprint. */
public interface ScopeTokenEncoder {
  String encodeFingerprint(String fingerprint);
}
