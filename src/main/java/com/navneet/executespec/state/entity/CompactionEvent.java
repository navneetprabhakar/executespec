package com.navneet.executespec.state.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "compaction_event")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompactionEvent {

    @Id
    private String id;              // UUID

    private String runId;
    private String taskId;
    private Instant occurredAt;
    private Integer tokensBefore;
    private Integer tokensAfter;
}
