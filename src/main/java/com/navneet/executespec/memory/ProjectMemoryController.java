package com.navneet.executespec.memory;

import com.navneet.executespec.memory.entity.ProjectRunLedger;
import com.navneet.executespec.memory.repository.ProjectRunLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}/memory")
@RequiredArgsConstructor
public class ProjectMemoryController {

    private final ProjectMemoryProvider memoryProvider;
    private final ProjectRunLedgerRepository ledgerRepo;

    @GetMapping
    public ResponseEntity<ProjectMemoryResponse> getMemory(@PathVariable String projectId) {
        String context = memoryProvider.buildPlannerContext(projectId);
        var stats = memoryProvider.getStats(projectId);
        var recent = ledgerRepo
                .findTop10ByProjectIdAndCompactedIntoIsNullOrderByRunNumberDesc(projectId);
        return ResponseEntity.ok(new ProjectMemoryResponse(context, stats, recent));
    }

    @GetMapping("/ledger")
    public ResponseEntity<List<ProjectRunLedger>> getLedger(@PathVariable String projectId) {
        return ResponseEntity.ok(ledgerRepo.findByProjectIdOrderByRunNumberDesc(projectId));
    }

    public record ProjectMemoryResponse(
            String fullContext,
            ProjectMemoryProvider.MemoryStats stats,
            List<ProjectRunLedger> recentEntries) {}
}
