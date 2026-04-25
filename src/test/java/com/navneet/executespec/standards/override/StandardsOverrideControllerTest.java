package com.navneet.executespec.standards.override;

import com.navneet.executespec.activity.ActivityEventEmitter;
import com.navneet.executespec.activity.ActivityEventType;
import com.navneet.executespec.standards.OrgStandardRepository;
import com.navneet.executespec.standards.ProjectStandardOverride;
import com.navneet.executespec.standards.ProjectStandardOverrideRepository;
import com.navneet.executespec.standards.StandardsViolation;
import com.navneet.executespec.standards.StandardsViolationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StandardsOverrideControllerTest {

    @Mock ProjectStandardOverrideRepository overrideRepo;
    @Mock StandardsViolationRepository violationRepo;
    @Mock OrgStandardRepository orgStandardRepo;
    @Mock ActivityEventEmitter emitter;
    @Mock ObjectProvider<ActivityEventEmitter> emitterProvider;

    @InjectMocks StandardsOverrideController controller;

    String projectId;
    UUID violationId;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID().toString();
        violationId = UUID.randomUUID();
        lenient().when(emitterProvider.getIfAvailable()).thenReturn(emitter);
    }

    @Test
    void emergencyOverride_persistsAndEmitsEvent() {
        StandardsViolation v = StandardsViolation.builder()
                .id(violationId).projectId(projectId).standardId("std-a")
                .enforcementType("LINT").severity("BLOCKING")
                .violationSummary("test").build();
        when(violationRepo.findById(violationId)).thenReturn(Optional.of(v));

        controller.emergencyOverride(projectId, violationId,
                new StandardsOverrideController.OverrideRequest(
                        "Approving for hotfix release; long-form rationale here"),
                fakeAuth());

        verify(violationRepo).save(argThat(saved -> saved.isOverrideApplied()
                && saved.getOverrideRationale().contains("Approving")));
        verify(emitter).emit(any(), eq(ActivityEventType.STANDARDS_OVERRIDE_APPLIED),
                any(), eq("VIOLATION"), eq(violationId.toString()), anyString(), anyMap());
    }

    @Test
    void emergencyOverride_rejectsShortRationale() {
        assertThatThrownBy(() -> controller.emergencyOverride(projectId, violationId,
                new StandardsOverrideController.OverrideRequest("too short"),
                fakeAuth()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(violationRepo);
    }

    @Test
    void emergencyOverride_404OnUnknownViolation() {
        when(violationRepo.findById(violationId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.emergencyOverride(projectId, violationId,
                new StandardsOverrideController.OverrideRequest("This is a long-enough rationale string"),
                fakeAuth()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void emergencyOverride_403WhenViolationBelongsToDifferentProject() {
        StandardsViolation v = StandardsViolation.builder()
                .id(violationId).projectId("other-project").standardId("x")
                .enforcementType("LINT").severity("BLOCKING").violationSummary("t").build();
        when(violationRepo.findById(violationId)).thenReturn(Optional.of(v));
        assertThatThrownBy(() -> controller.emergencyOverride(projectId, violationId,
                new StandardsOverrideController.OverrideRequest("This is a long-enough rationale string"),
                fakeAuth()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void addProjectException_persistsNotApplicable_andEmits() {
        when(overrideRepo.findByProjectIdAndStandardId(projectId, "std-a"))
                .thenReturn(Optional.empty());
        when(overrideRepo.save(any(ProjectStandardOverride.class))).thenAnswer(i -> i.getArgument(0));

        controller.addProjectException(projectId, "std-a",
                new StandardsOverrideController.ExceptionRequest(
                        "NOT_APPLICABLE", null,
                        "Not applicable in this project — Java legal exemption granted by infosec"),
                fakeAuth());

        verify(overrideRepo).save(argThat(o ->
                "NOT_APPLICABLE".equals(o.getOverrideType())
                && o.getRationale().contains("Not applicable")));
        verify(emitter).emit(any(), eq(ActivityEventType.STANDARDS_EXCEPTION_ADDED),
                any(), any(), any(), anyString(), anyMap());
    }

    @Test
    void addProjectException_severityDowngradeRequiresNewSeverity() {
        assertThatThrownBy(() -> controller.addProjectException(projectId, "std-a",
                new StandardsOverrideController.ExceptionRequest(
                        "SEVERITY_DOWNGRADE", null,
                        "Project-wide downgrade for migration phase — keep flag for tracking"),
                fakeAuth()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void removeProjectException_deletesAndEmits() {
        ProjectStandardOverride existing = ProjectStandardOverride.builder()
                .id(UUID.randomUUID()).projectId(projectId).standardId("std-a")
                .overrideType("NOT_APPLICABLE").rationale("x").build();
        when(overrideRepo.findByProjectIdAndStandardId(projectId, "std-a"))
                .thenReturn(Optional.of(existing));

        controller.removeProjectException(projectId, "std-a", fakeAuth());

        verify(overrideRepo).delete(existing);
        verify(emitter).emit(any(), eq(ActivityEventType.STANDARDS_EXCEPTION_REMOVED),
                any(), any(), any(), anyString(), anyMap());
    }

    private UsernamePasswordAuthenticationToken fakeAuth() {
        return new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(), "n/a");
    }
}
