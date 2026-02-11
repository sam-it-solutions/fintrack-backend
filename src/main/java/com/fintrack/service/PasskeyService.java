package com.fintrack.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.dto.AuthResponse;
import com.fintrack.dto.PasskeyStartResponse;
import com.fintrack.model.PasskeyChallenge;
import com.fintrack.model.PasskeyCredential;
import com.fintrack.model.User;
import com.fintrack.repository.PasskeyChallengeRepository;
import com.fintrack.repository.PasskeyCredentialRepository;
import com.fintrack.repository.UserRepository;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.data.UserIdentity;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PasskeyService {
  private static final Duration CHALLENGE_TTL = Duration.ofMinutes(10);

  private final RelyingParty relyingParty;
  private final ObjectMapper objectMapper;
  private final PasskeyChallengeRepository challengeRepository;
  private final PasskeyCredentialRepository credentialRepository;
  private final UserRepository userRepository;
  private final JwtService jwtService;

  public PasskeyService(RelyingParty relyingParty,
                        ObjectMapper objectMapper,
                        PasskeyChallengeRepository challengeRepository,
                        PasskeyCredentialRepository credentialRepository,
                        UserRepository userRepository,
                        JwtService jwtService) {
    this.relyingParty = relyingParty;
    this.objectMapper = objectMapper;
    this.challengeRepository = challengeRepository;
    this.credentialRepository = credentialRepository;
    this.userRepository = userRepository;
    this.jwtService = jwtService;
  }

  public PasskeyStartResponse startRegistration(UUID userId) {
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    UserIdentity identity = UserIdentity.builder()
        .name(user.getEmail())
        .displayName(user.getEmail())
        .id(new ByteArray(uuidToBytes(user.getId())))
        .build();
    PublicKeyCredentialCreationOptions options = relyingParty.startRegistration(
        StartRegistrationOptions.builder()
            .user(identity)
            .build());
    String json = toJson(options);
    PasskeyChallenge challenge = new PasskeyChallenge();
    challenge.setUser(user);
    challenge.setType("REGISTRATION");
    challenge.setOptionsJson(json);
    challengeRepository.save(challenge);
    return new PasskeyStartResponse(challenge.getId(), toJsonNode(json));
  }

  public void finishRegistration(UUID userId, UUID challengeId, JsonNode credential) {
    PasskeyChallenge challenge = challengeRepository.findByIdAndType(challengeId, "REGISTRATION")
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Challenge not found"));
    if (challenge.getUser() == null || !challenge.getUser().getId().equals(userId)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid challenge");
    }
    ensureNotExpired(challenge);
    PublicKeyCredentialCreationOptions options = readCreationOptions(challenge.getOptionsJson());
    RegistrationResult result;
    try {
      PublicKeyCredential<com.yubico.webauthn.data.AuthenticatorAttestationResponse,
          com.yubico.webauthn.data.ClientRegistrationExtensionOutputs> pkc =
          PublicKeyCredential.parseRegistrationResponseJson(credential.toString());
      result = relyingParty.finishRegistration(
          FinishRegistrationOptions.builder()
              .request(options)
              .response(pkc)
              .build());
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passkey registratie mislukt");
    }
    PasskeyCredential stored = new PasskeyCredential();
    stored.setUser(challenge.getUser());
    stored.setCredentialId(Base64Url.encode(result.getKeyId().getId().getBytes()));
    stored.setPublicKeyCose(result.getPublicKeyCose().getBytes());
    stored.setSignatureCount(result.getSignatureCount());
    credentialRepository.save(stored);
    challengeRepository.delete(challenge);
  }

  public PasskeyStartResponse startAuthentication(String email) {
    if (email == null || email.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email required");
    }
    User user = userRepository.findByEmail(email)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    AssertionRequest request = relyingParty.startAssertion(
        StartAssertionOptions.builder()
            .username(user.getEmail())
            .build());
    PasskeyChallenge challenge = new PasskeyChallenge();
    challenge.setUser(user);
    challenge.setType("AUTHENTICATION");
    challenge.setOptionsJson(toJson(request));
    challengeRepository.save(challenge);
    try {
      return new PasskeyStartResponse(challenge.getId(), toJsonNode(request.toCredentialsGetJson()));
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Challenge error");
    }
  }

  public AuthResponse finishAuthentication(UUID challengeId, JsonNode credential) {
    PasskeyChallenge challenge = challengeRepository.findByIdAndType(challengeId, "AUTHENTICATION")
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Challenge not found"));
    ensureNotExpired(challenge);
    AssertionRequest assertionRequest = readAssertionRequest(challenge.getOptionsJson());
    var result = finishAssertion(assertionRequest, credential);
    if (!result.isSuccess()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid passkey");
    }
    User user = userRepository.findByEmail(result.getUsername())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    updateSignatureCount(result.getCredentialId(), result.getSignatureCount());
    challengeRepository.delete(challenge);
    String token = jwtService.generateToken(user.getId(), user.getEmail());
    return new AuthResponse(token, user.getId());
  }

  private void updateSignatureCount(ByteArray credentialId, long signatureCount) {
    String encoded = Base64Url.encode(credentialId.getBytes());
    Optional<PasskeyCredential> stored = credentialRepository.findByCredentialId(encoded);
    if (stored.isEmpty()) {
      return;
    }
    PasskeyCredential cred = stored.get();
    if (signatureCount > cred.getSignatureCount()) {
      cred.setSignatureCount(signatureCount);
    }
    cred.setLastUsedAt(Instant.now());
    credentialRepository.save(cred);
  }

  private com.yubico.webauthn.AssertionResult finishAssertion(
      AssertionRequest request, JsonNode credential) {
    try {
      PublicKeyCredential<com.yubico.webauthn.data.AuthenticatorAssertionResponse,
          com.yubico.webauthn.data.ClientAssertionExtensionOutputs> pkc =
          PublicKeyCredential.parseAssertionResponseJson(credential.toString());
      return relyingParty.finishAssertion(
          FinishAssertionOptions.builder()
              .request(request)
              .response(pkc)
              .build());
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passkey login mislukt");
    }
  }

  private void ensureNotExpired(PasskeyChallenge challenge) {
    if (challenge.getCreatedAt() != null
        && challenge.getCreatedAt().isBefore(Instant.now().minus(CHALLENGE_TTL))) {
      challengeRepository.delete(challenge);
      throw new ResponseStatusException(HttpStatus.GONE, "Challenge expired");
    }
  }

  private PublicKeyCredentialCreationOptions readCreationOptions(String json) {
    try {
      return PublicKeyCredentialCreationOptions.fromJson(json);
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid challenge");
    }
  }

  private AssertionRequest readAssertionRequest(String json) {
    try {
      return AssertionRequest.fromJson(json);
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid challenge");
    }
  }

  private String toJson(Object value) {
    try {
      if (value instanceof PublicKeyCredentialCreationOptions) {
        return ((PublicKeyCredentialCreationOptions) value).toJson();
      }
      if (value instanceof AssertionRequest) {
        return ((AssertionRequest) value).toJson();
      }
      return objectMapper.writeValueAsString(value);
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "JSON error");
    }
  }

  private JsonNode toJsonNode(String json) {
    try {
      return objectMapper.readTree(json);
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "JSON error");
    }
  }

  private static byte[] uuidToBytes(UUID uuid) {
    ByteBuffer buffer = ByteBuffer.allocate(16);
    buffer.putLong(uuid.getMostSignificantBits());
    buffer.putLong(uuid.getLeastSignificantBits());
    return buffer.array();
  }

  private static class Base64Url {
    static String encode(byte[] bytes) {
      return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
  }
}
