package com.bento.crm.foundation;

import com.bento.crm.common.config.TenantFilterInterceptor;
import com.bento.crm.support.IntegrationTestBase;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins down what {@link TenantFilterInterceptor}'s Hibernate {@code organizationFilter} actually
 * does for code running downstream of it.
 *
 * <p>The interceptor enables the filter on {@code entityManager.unwrap(Session.class)} while it is
 * still in the servlet filter chain — before Spring MVC's open-in-view interceptor binds the
 * request's EntityManager and before any service opens a transaction. This test runs an unscoped
 * JPQL query inside that same filter chain, the way a service method would, and records whether
 * the other tenant's rows leak into it.
 *
 * <p>The finding (asserted below) is that they do: the filter is enabled on a session nothing else
 * uses, so it is not a safety net. Every repository query must carry its own
 * {@code organization_id} predicate — which is the rule the WhatsApp inbox code follows.
 */
class TenantFilterBehaviourTest extends IntegrationTestBase {

    @Autowired
    private TenantFilterInterceptor tenantFilterInterceptor;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void organizationFilterDoesNotScopeQueriesRunDownstreamOfTheServletFilter() throws Exception {
        String marker = "tenant-filter-" + System.nanoTime();
        String tokenA = signUpAndLogin();
        createPartner(tokenA, marker + "-A");
        String tokenB = signUpAndLogin();
        createPartner(tokenB, marker + "-B");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/partners");
        request.setContextPath("/api/v1");
        request.addHeader("Authorization", "Bearer " + tokenB);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<List<String>> seenByOrgB = new AtomicReference<>();
        tenantFilterInterceptor.doFilter(request, response, (req, res) ->
                seenByOrgB.set(new TransactionTemplate(transactionManager).execute(tx ->
                        entityManager.createQuery(
                                        "SELECT p.name FROM Partner p WHERE p.name LIKE :marker ORDER BY p.name",
                                        String.class)
                                .setParameter("marker", marker + "%")
                                .getResultList())));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seenByOrgB.get())
                .as("rows visible to an unscoped query issued inside org B's request")
                .containsExactly(marker + "-A", marker + "-B");
    }

    private void createPartner(String token, String name) throws Exception {
        mockMvc.perform(post("/partners")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "LEAD", "name": "%s", "email": "%s@example.com"}
                                """.formatted(name, name)))
                .andExpect(status().isCreated());
    }
}
