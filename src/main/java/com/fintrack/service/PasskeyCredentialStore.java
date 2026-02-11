package com.fintrack.service;

import com.fintrack.model.PasskeyCredential;
import com.fintrack.model.User;
import com.fintrack.repository.PasskeyCredentialRepository;
import com.fintrack.repository.UserRepository;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class PasskeyCredentialStore implements CredentialRepository {
  private final PasskeyCredentialRepository credentialRepository;
  private final UserRepository userRepository;

  public PasskeyCredentialStore(PasskeyCredentialRepository credentialRepository, UserRepository userRepository) {
    this.credentialRepository = credentialRepository;
    this.userRepository = userRepository;
  }

  @Override
  public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
    if (username == null || username.isBlank()) {
      return Collections.emptySet();
    }
    Optional<User> user = userRepository.findByEmail(username);
    if (user.isEmpty()) {
      return Collections.emptySet();
    }
    List<PasskeyCredential> creds = credentialRepository.findByUser(user.get());
    return creds.stream()
        .map(cred -> PublicKeyCredentialDescriptor.builder()
            .id(new ByteArray(Base64Url.decode(cred.getCredentialId())))
            .build())
        .collect(Collectors.toSet());
  }

  @Override
  public Optional<ByteArray> getUserHandleForUsername(String username) {
    if (username == null || username.isBlank()) {
      return Optional.empty();
    }
    return userRepository.findByEmail(username).map(user -> new ByteArray(uuidToBytes(user.getId())));
  }

  @Override
  public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
    if (userHandle == null) {
      return Optional.empty();
    }
    UUID userId = bytesToUuid(userHandle.getBytes());
    return userRepository.findById(userId).map(User::getEmail);
  }

  @Override
  public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
    if (credentialId == null || userHandle == null) {
      return Optional.empty();
    }
    String encoded = Base64Url.encode(credentialId.getBytes());
    Optional<PasskeyCredential> cred = credentialRepository.findByCredentialId(encoded);
    if (cred.isEmpty()) {
      return Optional.empty();
    }
    UUID expectedUser = cred.get().getUser().getId();
    UUID actualUser = bytesToUuid(userHandle.getBytes());
    if (!expectedUser.equals(actualUser)) {
      return Optional.empty();
    }
    return Optional.of(toRegistered(cred.get()));
  }

  @Override
  public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
    if (credentialId == null) {
      return Collections.emptySet();
    }
    String encoded = Base64Url.encode(credentialId.getBytes());
    return credentialRepository.findAllByCredentialId(encoded).stream()
        .map(this::toRegistered)
        .collect(Collectors.toSet());
  }

  private RegisteredCredential toRegistered(PasskeyCredential cred) {
    return RegisteredCredential.builder()
        .credentialId(new ByteArray(Base64Url.decode(cred.getCredentialId())))
        .userHandle(new ByteArray(uuidToBytes(cred.getUser().getId())))
        .publicKeyCose(new ByteArray(cred.getPublicKeyCose()))
        .signatureCount(cred.getSignatureCount())
        .build();
  }

  private static byte[] uuidToBytes(UUID uuid) {
    ByteBuffer buffer = ByteBuffer.allocate(16);
    buffer.putLong(uuid.getMostSignificantBits());
    buffer.putLong(uuid.getLeastSignificantBits());
    return buffer.array();
  }

  private static UUID bytesToUuid(byte[] bytes) {
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    return new UUID(buffer.getLong(), buffer.getLong());
  }

  private static class Base64Url {
    static String encode(byte[] bytes) {
      return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] decode(String value) {
      return java.util.Base64.getUrlDecoder().decode(value);
    }
  }
}
