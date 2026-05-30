package com.navneet.executespec.state.entity;

import java.time.Instant;

import jakarta.persistence.*;

import com.navneet.executespec.tenant.TenantInterceptor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@EntityListeners(TenantInterceptor.class)
@Table(name = "agent_message")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentMessage {

  @Id private String messageId; // UUID

  private String runId;
  private String fromAgent; // "planner" | "worker-{taskId}" | "reviewer" | "harness"
  private String toAgent;
  private String messageType; // TASK_ASSIGNMENT | TOOL_CALL | COMPLETION_REPORT |

  // REVIEW | COORDINATION | QUESTION | BLOCKER | FYI

  @Column(columnDefinition = "TEXT")
  private String contentSummary; // short description for log/viz (not full content)

  private Instant sentAt;
  private Integer tokenCost; // approximate tokens consumed by this message

  private Boolean fileLockConflict; // true if this message represents a file lock conflict

  private String taskId; // null for run-level messages; set for task activity log

  @Column(name = "tenant_id", nullable = false)
  @Builder.Default
  private String tenantId = "default";
}
