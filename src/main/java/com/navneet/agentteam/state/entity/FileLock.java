package com.navneet.agentteam.state.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "file_lock")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileLock {

    @Id
    private String filePath;            // relative to workspace root — PK

    private String runId;
    private String taskId;              // which worker owns this file
    private Instant claimedAt;
}
