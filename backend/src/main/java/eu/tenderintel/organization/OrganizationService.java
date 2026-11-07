package eu.tenderintel.organization;

import eu.tenderintel.audit.AuditAction;
import eu.tenderintel.audit.AuditService;
import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.common.id.Ids;
import eu.tenderintel.common.id.Tokens;
import eu.tenderintel.config.AppProperties;
import eu.tenderintel.identity.User;
import eu.tenderintel.identity.UserRepository;
import eu.tenderintel.mail.EmailOutbox;
import eu.tenderintel.mail.EmailTemplate;
import eu.tenderintel.organization.OrganizationDtos.InvitationView;
import eu.tenderintel.organization.OrganizationDtos.MemberView;
import eu.tenderintel.organization.OrganizationDtos.MembershipView;
import eu.tenderintel.organization.OrganizationDtos.OrganizationView;
import eu.tenderintel.organization.OrganizationDtos.UpdateOrganizationRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationService {

    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository members;
    private final OrganizationInvitationRepository invitations;
    private final OrganizationAccess access;
    private final UserRepository users;
    private final EmailOutbox outbox;
    private final AuditService audit;
    private final AppProperties props;
    private final Clock clock;

    OrganizationService(OrganizationRepository organizations, OrganizationMemberRepository members,
            OrganizationInvitationRepository invitations, OrganizationAccess access, UserRepository users,
            EmailOutbox outbox, AuditService audit, AppProperties props, Clock clock) {
        this.organizations = organizations;
        this.members = members;
        this.invitations = invitations;
        this.access = access;
        this.users = users;
        this.outbox = outbox;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    /** Every new user gets an organization in which they are COMPANY_ADMIN (the "User" role from the spec). */
    @Transactional
    public UUID createForNewUser(UUID userId, String name) {
        Instant now = Instant.now(clock);
        String base = Slugs.of(name);
        String slug = base;
        while (organizations.existsBySlug(slug)) {
            slug = base + "-" + Tokens.newSecret().substring(0, 6).toLowerCase().replaceAll("[^a-z0-9]", "x");
        }
        Organization org = organizations.save(new Organization(Ids.newId(), name, slug, now));
        members.save(new OrganizationMember(org.getId(), userId, OrganizationRole.COMPANY_ADMIN, null, now));
        audit.record(AuditAction.ORGANIZATION_CREATED, userId, org.getId(), "organization", org.getId(), Map.of());
        return org.getId();
    }

    @Transactional(readOnly = true)
    public List<MembershipView> listForUser(UUID userId) {
        List<OrganizationMember> memberships = members.findByUserIdOrderByJoinedAt(userId);
        Map<UUID, Organization> orgs = organizations.findAllById(
                memberships.stream().map(OrganizationMember::getOrganizationId).toList()).stream()
                .collect(Collectors.toMap(Organization::getId, Function.identity()));
        return memberships.stream()
                .filter(m -> orgs.containsKey(m.getOrganizationId()))
                .map(m -> {
                    Organization o = orgs.get(m.getOrganizationId());
                    return new MembershipView(o.getId(), o.getName(), o.getSlug(), o.getPlanCode(), m.getRole());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public OrganizationView get(UUID orgId, UUID userId) {
        access.requireMember(orgId, userId);
        return OrganizationView.of(load(orgId));
    }

    @Transactional
    public OrganizationView update(UUID orgId, UUID userId, UpdateOrganizationRequest req) {
        access.requireAdmin(orgId, userId);
        Organization org = load(orgId);
        org.update(req.name(), req.countryCode(), req.vatId(), req.billingEmail(), Instant.now(clock));
        audit.record(AuditAction.ORGANIZATION_UPDATED, userId, orgId, "organization", orgId, Map.of());
        return OrganizationView.of(org);
    }

    @Transactional(readOnly = true)
    public List<MemberView> members(UUID orgId, UUID userId) {
        access.requireMember(orgId, userId);
        List<OrganizationMember> list = members.findByOrganizationIdOrderByJoinedAt(orgId);
        Map<UUID, User> byId = users.findAllById(list.stream().map(OrganizationMember::getUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return list.stream()
                .filter(m -> byId.containsKey(m.getUserId()))
                .map(m -> {
                    User u = byId.get(m.getUserId());
                    return new MemberView(u.getId(), u.getEmail(), u.getFullName(), m.getRole(), m.getJoinedAt());
                })
                .toList();
    }

    @Transactional
    public void changeRole(UUID orgId, UUID actorId, UUID memberUserId, OrganizationRole role) {
        access.requireAdmin(orgId, actorId);
        OrganizationMember member = access.requireMember(orgId, memberUserId);
        if (member.getRole() == role) {
            return;
        }
        if (member.getRole() == OrganizationRole.COMPANY_ADMIN && members.countAdmins(orgId) <= 1) {
            throw ApiException.conflict("last_admin", "An organization must keep at least one COMPANY_ADMIN");
        }
        OrganizationRole previous = member.getRole();
        member.changeRole(role);
        audit.record(AuditAction.MEMBER_ROLE_CHANGED, actorId, orgId, "user", memberUserId,
                Map.of("from", previous.name(), "to", role.name()));
    }

    /** Admins may remove anyone; any member may remove themselves (leave). */
    @Transactional
    public void removeMember(UUID orgId, UUID actorId, UUID memberUserId) {
        OrganizationMember actor = access.requireMember(orgId, actorId);
        if (!actorId.equals(memberUserId) && actor.getRole() != OrganizationRole.COMPANY_ADMIN) {
            throw ApiException.forbidden();
        }
        OrganizationMember member = access.requireMember(orgId, memberUserId);
        if (member.getRole() == OrganizationRole.COMPANY_ADMIN && members.countAdmins(orgId) <= 1) {
            throw ApiException.conflict("last_admin", "An organization must keep at least one COMPANY_ADMIN");
        }
        members.delete(member);
        audit.record(AuditAction.MEMBER_REMOVED, actorId, orgId, "user", memberUserId, Map.of());
    }

    @Transactional
    public InvitationView invite(UUID orgId, UUID actorId, String email, OrganizationRole role) {
        access.requireAdmin(orgId, actorId);
        Instant now = Instant.now(clock);
        String normalized = email.trim();
        users.findByEmail(normalized).ifPresent(existing -> {
            if (members.findByOrganizationIdAndUserId(orgId, existing.getId()).isPresent()) {
                throw ApiException.conflict("already_member", "This person is already a member");
            }
        });
        invitations.findOpenByEmail(orgId, normalized).ifPresent(old -> {
            old.revoke(now);
            invitations.saveAndFlush(old);
        });
        String token = Tokens.newSecret();
        OrganizationInvitation invitation = invitations.save(new OrganizationInvitation(Ids.newId(), orgId,
                normalized, role, Tokens.sha256(token), actorId, now.plus(props.security().invitationTtl()), now));
        User inviter = users.findById(actorId).orElseThrow();
        Organization org = load(orgId);
        outbox.enqueue(normalized, EmailTemplate.ORGANIZATION_INVITATION, inviter.getLocale(), inviter.getFullName(),
                org.getName(), props.publicUrl() + "/invitations/accept?token=" + token);
        audit.record(AuditAction.MEMBER_INVITED, actorId, orgId, "invitation", invitation.getId(),
                Map.of("role", role.name()));
        return InvitationView.of(invitation);
    }

    @Transactional(readOnly = true)
    public List<InvitationView> openInvitations(UUID orgId, UUID actorId) {
        access.requireAdmin(orgId, actorId);
        Instant now = Instant.now(clock);
        return invitations.findOpen(orgId).stream().filter(i -> i.isOpen(now)).map(InvitationView::of).toList();
    }

    @Transactional
    public void revokeInvitation(UUID orgId, UUID actorId, UUID invitationId) {
        access.requireAdmin(orgId, actorId);
        OrganizationInvitation invitation = invitations.findById(invitationId)
                .filter(i -> i.getOrganizationId().equals(orgId))
                .orElseThrow(() -> ApiException.notFound("Invitation"));
        invitation.revoke(Instant.now(clock));
        audit.record(AuditAction.INVITATION_REVOKED, actorId, orgId, "invitation", invitationId, Map.of());
    }

    /** The accepting user must be logged in with a verified e-mail equal to the invited address. */
    @Transactional
    public MembershipView acceptInvitation(String token, UUID userId) {
        Instant now = Instant.now(clock);
        OrganizationInvitation invitation = invitations.findByTokenHash(Tokens.sha256(token))
                .filter(i -> i.isOpen(now))
                .orElseThrow(() -> ApiException.badRequest("invalid_token", "Invitation is invalid or has expired"));
        User user = users.findById(userId).orElseThrow();
        if (!user.isEmailVerified() || !user.getEmail().equalsIgnoreCase(invitation.getEmail())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "invitation_email_mismatch",
                    "This invitation was sent to a different e-mail address");
        }
        UUID orgId = invitation.getOrganizationId();
        if (members.findByOrganizationIdAndUserId(orgId, userId).isEmpty()) {
            members.save(new OrganizationMember(orgId, userId, invitation.getRole(), null, now));
        }
        invitation.accept(now);
        audit.record(AuditAction.INVITATION_ACCEPTED, userId, orgId, "invitation", invitation.getId(), Map.of());
        Organization org = load(orgId);
        OrganizationRole role = access.requireMember(orgId, userId).getRole();
        return new MembershipView(org.getId(), org.getName(), org.getSlug(), org.getPlanCode(), role);
    }

    private Organization load(UUID orgId) {
        return organizations.findById(orgId).orElseThrow(() -> ApiException.notFound("Organization"));
    }
}
