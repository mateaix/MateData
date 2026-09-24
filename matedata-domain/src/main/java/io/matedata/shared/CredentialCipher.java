package io.matedata.shared;

/** Encrypts stored credentials and decrypts them only at the point of use. */
public interface CredentialCipher {
  String encrypt(String plaintext);

  String decrypt(String ciphertext);
}
