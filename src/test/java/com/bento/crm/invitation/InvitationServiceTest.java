package com.bento.crm.invitation;

import com.bento.crm.auth.dto.LoginResponse;
import com.bento.crm.auth.service.AuthService;
import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.mail.EmailService;
import com.bento.crm.common.model.UserRole;
import com.bento.crm.identity.model.AppUser;
import com.bento.crm.identity.model.Team;
import com.bento.crm.identity.repository.AccountRepository;
import com.bento.crm.identity.repository.AppUserRepository;
import com.bento.crm.identity.repository.TeamRepository;
import com.bento.crm.invitation.dto.CreateInvitationRequest;
import com.bento.crm.invitation.dto.InvitationResponse;
import com.bento.crm.invitation.model.InvitationStatus;
import com.bento.crm.invitation.model.UserInvitation;
import com.bento.crm.invitation.repository.UserInvitationRepository;
import com.bento.crm.invitation.service.InvitationProperties;
import com.bento.crm.invitation.service.InvitationService;
import com.bento.crm.organization.model.Organization;
import com.bento.crm.organization.repository.OrganizationRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.Filter;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvitationServiceTest {

    @Mock
    private UserInvitationRepository invitationRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private AppUserRepository userRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private TeamRepository teamRepository;
    @Mock
    private EmailService emailService;
    @Mock
    private InvitationProperties properties;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuthService authService;
    @Mock
    private EntityManager entityManager;
    @Mock
    private Session session;

    @InjectMocks
    private InvitationService invitationService;

    private UUID currentOrgId;

    @BeforeEach
    void setUp() {
        currentOrgId = UUID.randomUUID();
        TenantContext.setCurrentOrganizationId(currentOrgId);
        lenient().when(properties.getExpiryDays()).thenReturn(7);
        lenient().when(properties.getAcceptUrl()).thenReturn("http://localhost:3000/invite/accept");
        lenient().when(entityManager.unwrap(Session.class)).thenReturn(session);
        lenient().when(session.enableFilter(anyString())).thenReturn(mock(Filter.class));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void inviteCreatesAPendingInvitationWithItsAcceptLink() {
        String email = "adnanetouta@crmbento.com";
        UUID teamId = UUID.randomUUID();
        CreateInvitationRequest request = new CreateInvitationRequest();
        request.setEmail(email);
        request.setRole("MANAGER");
        request.setTeamId(teamId.toString());
        request.setDisplayName("Adnan");
        request.setJobTitle("Marketer");

        when(userRepository.findByOrganizationIdAndEmail(currentOrgId, email)).thenReturn(Optional.empty());
        when(invitationRepository.findByOrganizationIdAndEmailAndStatus(currentOrgId, email, InvitationStatus.PENDING))
                .thenReturn(Optional.empty());

        when(invitationRepository.save(any(UserInvitation.class))).thenAnswer(invocation -> {
            UserInvitation inv = invocation.getArgument(0);
            inv.setId(UUID.randomUUID());
            return inv;
        });

        Organization org = Organization.builder().name("Bento Team Org").build();
        org.setId(currentOrgId);
        when(organizationRepository.findById(currentOrgId)).thenReturn(Optional.of(org));

        Team team = Team.builder().name("Marketing Team").build();
        team.setId(teamId);
        when(teamRepository.findByOrganizationIdAndId(currentOrgId, teamId)).thenReturn(Optional.of(team));
        when(teamRepository.findById(teamId)).thenReturn(Optional.of(team));

        InvitationResponse response = invitationService.invite(request);

        assertThat(response).isNotNull();
        assertThat(response.getEmail()).isEqualTo(email);
        assertThat(response.getRole()).isEqualTo("MANAGER");
        assertThat(response.getTeamName()).isEqualTo("Marketing Team");
        assertThat(response.getInvitationUrl()).contains("http://localhost:3000/invite/accept?token=");
        assertThat(response.getToken()).isNotBlank();

    }

    @Test
    void acceptForLoggedInUserJoinsTargetOrgWithPreAssignedRoleAndTeam() {
        UUID invitationId = UUID.randomUUID();
        UUID targetOrgId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID currentUserId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        String email = "adnanetouta@crmbento.com";

        UserInvitation invitation = UserInvitation.builder()
                .email(email)
                .role(UserRole.MANAGER)
                .teamId(teamId)
                .jobTitle("Marketer")
                .status(InvitationStatus.PENDING)
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        invitation.setId(invitationId);
        invitation.setOrganizationId(targetOrgId);

        when(invitationRepository.findByTokenHash(anyString())).thenReturn(Optional.of(invitation));

        AppUser currentUser = AppUser.builder()
                .email(email)
                .accountId(accountId)
                .displayName("Adnan Touta")
                .role(UserRole.VIEWER)
                .jobTitle("Marketer")
                .isActive(true)
                .build();
        currentUser.setId(currentUserId);
        currentUser.setOrganizationId(UUID.randomUUID());

        when(userRepository.findById(currentUserId)).thenReturn(Optional.of(currentUser));
        when(userRepository.findByAccountIdAndOrganizationIdAcrossOrganizations(accountId, targetOrgId)).thenReturn(Optional.empty());
        when(teamRepository.findByOrganizationIdAndId(targetOrgId, teamId)).thenReturn(Optional.of(Team.builder().build()));

        when(userRepository.save(any(AppUser.class))).thenAnswer(invocationOnMock -> {
            AppUser u = invocationOnMock.getArgument(0);
            if (u.getId() == null) {
                u.setId(UUID.randomUUID());
            }
            return u;
        });

        LoginResponse expectedLogin = LoginResponse.builder()
                .accessToken("new-jwt-access-token")
                .refreshToken("new-jwt-refresh-token")
                .build();
        when(authService.issueSession(any(AppUser.class))).thenReturn(expectedLogin);

        LoginResponse loginResponse = invitationService.acceptForLoggedInUser(invitationId, "emailed-token", currentUserId);

        assertThat(loginResponse).isNotNull();
        assertThat(loginResponse.getAccessToken()).isEqualTo("new-jwt-access-token");
        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(invitation.getAcceptedUserId()).isNotNull();

        // The membership the user signed in with stays active; joining adds a second one.
        assertThat(currentUser.getIsActive()).isTrue();

        ArgumentCaptor<AppUser> userCaptor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(userCaptor.capture());
        AppUser savedUser = userCaptor.getValue();
        assertThat(savedUser.getOrganizationId()).isEqualTo(targetOrgId);
        assertThat(savedUser.getEmail()).isEqualTo(email);
        assertThat(savedUser.getRole()).isEqualTo(UserRole.MANAGER);
        assertThat(savedUser.getTeamId()).isEqualTo(teamId);
        // The new Membership belongs to the same Account, so the person keeps one password.
        assertThat(savedUser.getAccountId()).isEqualTo(accountId);
    }

    @Test
    void acceptForLoggedInUserRejectsATokenIssuedForAnotherInvitation() {
        UserInvitation otherInvitation = UserInvitation.builder()
                .email("someone@crmbento.com")
                .role(UserRole.ADMIN)
                .status(InvitationStatus.PENDING)
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        otherInvitation.setId(UUID.randomUUID());
        otherInvitation.setOrganizationId(UUID.randomUUID());
        when(invitationRepository.findByTokenHash(anyString())).thenReturn(Optional.of(otherInvitation));

        assertThatThrownBy(() -> invitationService.acceptForLoggedInUser(UUID.randomUUID(), "emailed-token", UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(userRepository, never()).save(any(AppUser.class));
    }

    @Test
    void inviteThrowsWhenTeamDoesNotExistInOrganization() {
        String email = "someone@crmbento.com";
        UUID nonExistentTeamId = UUID.randomUUID();
        CreateInvitationRequest request = new CreateInvitationRequest();
        request.setEmail(email);
        request.setRole("SALESPERSON");
        request.setTeamId(nonExistentTeamId.toString());

        when(userRepository.findByOrganizationIdAndEmail(currentOrgId, email)).thenReturn(Optional.empty());
        when(invitationRepository.findByOrganizationIdAndEmailAndStatus(currentOrgId, email, InvitationStatus.PENDING))
                .thenReturn(Optional.empty());
        when(teamRepository.findByOrganizationIdAndId(currentOrgId, nonExistentTeamId)).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(
                com.bento.crm.common.exception.ResourceNotFoundException.class,
                () -> invitationService.invite(request)
        );
    }
}
