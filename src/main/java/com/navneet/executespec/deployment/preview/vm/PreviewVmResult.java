package com.navneet.executespec.deployment.preview.vm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PreviewVmResult {

  private String url;
  private int port;

  @JsonProperty("preview_id")
  private String previewId;

  @JsonProperty("vm_ip")
  private String vmIp;

  @JsonProperty("created_at")
  private String createdAt;

  @JsonProperty("expires_at")
  private String expiresAt;

  private boolean healthy;
}
