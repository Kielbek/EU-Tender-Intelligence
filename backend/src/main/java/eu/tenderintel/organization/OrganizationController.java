package eu.tenderintel.organization;

import eu.tenderintel.organization.OrganizationDtos.AcceptInvitationRequest;
import eu.tenderintel.organization.OrganizationDtos.ChangeRoleRequest;
import eu.tenderintel.organization.OrganizationDtos.InvitationView;
import eu.tenderintel.organization.OrganizationDtos.InviteRequest;
import eu.tenderintel.organization.OrganizationDtos.MemberView;
import eu.tenderintel.organization.OrganizationDtos.MembershipView;
import eu.tenderintel.organization.OrganizationDtos.OrganizationView;
import eu.tenderintel.organization.OrganizationDtos.UpdateOrganizationRequest;
import eu.tenderintel.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Organizations", description = "Organizacje (tenanty), członkowie, role i zaproszenia")
@SecurityRequirement(name = "bearer")
class OrganizationController {

    private final OrganizationService service;

    OrganizationController(OrganizationService service) {
        this.service = service;
    }

    @GetMapping("/organizations")
    @Operation(summary = "Organizacje, do których należy zalogowany użytkownik")
    List<MembershipView> mine() {
        return service.listForUser(CurrentUser.id());
    }

    @GetMapping("/organizations/{orgId}")
    OrganizationView get(@PathVariable UUID orgId) {
        return service.get(orgId, CurrentUser.id());
    }

    @PatchMapping("/organizations/{orgId}")
    @Operation(summary = "Aktualizacja danych organizacji (COMPANY_ADMIN)")
    OrganizationView update(@PathVariable UUID orgId, @Valid @RequestBody UpdateOrganizationRequest req) {
        return service.update(orgId, CurrentUser.id(), req);
    }

    @GetMapping("/organizations/{orgId}/members")
    List<MemberView> members(@PathVariable UUID orgId) {
        return service.members(orgId, CurrentUser.id());
    }

    @PutMapping("/organizations/{orgId}/members/{userId}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Zmiana roli członka (COMPANY_ADMIN); nie można odebrać roli ostatniemu administratorowi")
    void changeRole(@PathVariable UUID orgId, @PathVariable UUID userId, @Valid @RequestBody ChangeRoleRequest req) {
        service.changeRole(orgId, CurrentUser.id(), userId, req.role());
    }

    @DeleteMapping("/organizations/{orgId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Usunięcie członka (COMPANY_ADMIN) lub opuszczenie organizacji (własne konto)")
    void removeMember(@PathVariable UUID orgId, @PathVariable UUID userId) {
        service.removeMember(orgId, CurrentUser.id(), userId);
    }

    @PostMapping("/organizations/{orgId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Zaproszenie osoby e-mailem (COMPANY_ADMIN)")
    InvitationView invite(@PathVariable UUID orgId, @Valid @RequestBody InviteRequest req) {
        return service.invite(orgId, CurrentUser.id(), req.email(), req.role());
    }

    @GetMapping("/organizations/{orgId}/invitations")
    List<InvitationView> invitations(@PathVariable UUID orgId) {
        return service.openInvitations(orgId, CurrentUser.id());
    }

    @DeleteMapping("/organizations/{orgId}/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable UUID orgId, @PathVariable UUID invitationId) {
        service.revokeInvitation(orgId, CurrentUser.id(), invitationId);
    }

    @PostMapping("/invitations/accept")
    @Operation(summary = "Przyjęcie zaproszenia (adres e-mail konta musi być zgodny z zaproszeniem)")
    MembershipView accept(@Valid @RequestBody AcceptInvitationRequest req) {
        return service.acceptInvitation(req.token(), CurrentUser.id());
    }
}
