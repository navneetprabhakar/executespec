package com.navneet.executespec.contact;

import java.util.Map;

import jakarta.validation.Valid;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/contact")
@RequiredArgsConstructor
public class ContactController {

  private final ContactService contactService;

  @PostMapping
  public ResponseEntity<Map<String, String>> submitFeedback(
      @AuthenticationPrincipal AppUserDetails principal,
      @Valid @RequestBody ContactRequest request) {

    AppUser user = principal.getUser();
    String fullName = user.getFirstName() + " " + user.getLastName();

    contactService.submitFeedback(user.getEmail(), fullName, request.subject(), request.body());

    return ResponseEntity.ok(Map.of("message", "Feedback submitted successfully"));
  }
}
