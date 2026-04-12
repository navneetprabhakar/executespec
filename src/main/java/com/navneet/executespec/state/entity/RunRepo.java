package com.navneet.executespec.state.entity;

import com.navneet.executespec.tenant.TenantInterceptor;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@EntityListeners(TenantInterceptor.class)
@Table(name = "run_repo")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunRepo {

    @Id
    private String id;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(name = "repo_url", nullable = false)
    private String repoUrl;

    @Column(name = "repo_owner", nullable = false)
    private String repoOwner;

    @Column(name = "repo_name", nullable = false)
    private String repoName;

    @Enumerated(EnumType.STRING)
    @Column(name = "repo_mode", nullable = false)
    private RepoMode repoMode;

    @Builder.Default
    @Column(nullable = false)
    private String branch = "main";

    @Column(name = "created_at", nullable = false)
    private String createdAt;

    @Column(name = "pushed_at")
    private String pushedAt;

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private String tenantId = "default";


    public enum RepoMode { NEW, EXISTING, NONE }

    public static RunRepo create(String runId, String repoUrl, String owner, String name,
                                  RepoMode mode) {
        RunRepo r = new RunRepo();
        r.id = UUID.randomUUID().toString();
        r.runId = runId;
        r.repoUrl = repoUrl;
        r.repoOwner = owner;
        r.repoName = name;
        r.repoMode = mode;
        r.branch = "main";
        r.createdAt = Instant.now().toString();
        return r;
    }

    public void markPushed() {
        this.pushedAt = Instant.now().toString();
    }
}
