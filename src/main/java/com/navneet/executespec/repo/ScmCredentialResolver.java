package com.navneet.executespec.repo;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/** Resolves an ephemeral provider credential from the authenticated user. */
@Component
@RequiredArgsConstructor
public class ScmCredentialResolver {
  private final AppUserRepository users;

  public ScmCredential require(String userId, ScmProvider provider) {
    AppUser user =
        users
            .findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    ScmCredential credential =
        switch (provider) {
          case GITHUB -> new ScmCredential(null, user.getGithubPat());
          case BITBUCKET ->
              new ScmCredential(user.getBitbucketUsername(), user.getBitbucketAppPassword());
        };
    if (!credential.usable()
        || (provider == ScmProvider.BITBUCKET
            && (credential.username() == null || credential.username().isBlank()))) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, provider + " credentials are not configured");
    }
    return credential;
  }
}
