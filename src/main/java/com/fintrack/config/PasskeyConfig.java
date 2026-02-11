package com.fintrack.config;

import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.AttestationConveyancePreference;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.fintrack.service.PasskeyCredentialStore;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PasskeyConfig {
  @Bean
  public RelyingParty relyingParty(PasskeyProperties properties, PasskeyCredentialStore credentialStore) {
    RelyingPartyIdentity identity = RelyingPartyIdentity.builder()
        .id(properties.rpId())
        .name(properties.rpName())
        .build();
    return RelyingParty.builder()
        .identity(identity)
        .credentialRepository(credentialStore)
        .origins(Set.of(properties.origin()))
        .attestationConveyancePreference(AttestationConveyancePreference.NONE)
        .build();
  }
}
