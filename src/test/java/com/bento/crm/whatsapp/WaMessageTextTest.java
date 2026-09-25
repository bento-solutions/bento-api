package com.bento.crm.whatsapp;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.bento.crm.whatsapp.util.WaMessageText.containsLink;
import static com.bento.crm.whatsapp.util.WaMessageText.isPersonalized;
import static com.bento.crm.whatsapp.util.WaMessageText.render;
import static org.assertj.core.api.Assertions.assertThat;

class WaMessageTextTest {

    @Test
    void linksAreFound_butNotEmailAddressesOrDecimals() {
        assertThat(containsLink("Voir https://crmbento.com/offre")).isTrue();
        assertThat(containsLink("Tout est sur www.crmbento.com")).isTrue();
        assertThat(containsLink("notre site crmbento.com/tarifs")).isTrue();
        assertThat(containsLink("inscription : bit.ly/abc")).isTrue();
        assertThat(containsLink("Écrivez à contact@crmbento.com")).isFalse();
        assertThat(containsLink("Remise de 20.5% jusqu'à 3.5 km")).isFalse();
        assertThat(containsLink("Bonjour, merci pour votre retour.")).isFalse();
    }

    @Test
    void render_fillsTheContactsDetails_andPicksTheSameVariantForTheSameContact() {
        String template = "{Bonjour|Salut} {{first_name}}, votre devis {{company}} est prêt.";
        String once = render(template, "Amine El Idrissi", "Atlas SARL", 42);
        assertThat(once).matches("(Bonjour|Salut) Amine, votre devis Atlas SARL est prêt\\.");
        assertThat(render(template, "Amine El Idrissi", "Atlas SARL", 42)).isEqualTo(once);

        Set<String> greetings = IntStream.range(0, 60)
                .mapToObj(i -> render("{Bonjour|Salut|Hello}", null, null, i)).collect(Collectors.toSet());
        assertThat(greetings).containsExactlyInAnyOrder("Bonjour", "Salut", "Hello");
    }

    @Test
    void aMissingNameLeavesNoGap_andFrenchSpacingStays() {
        assertThat(render("Bonjour {{first_name}}, offre de rentrée : -20% !", "", null, 1))
                .isEqualTo("Bonjour, offre de rentrée : -20% !");
        assertThat(render("Merci {{first_name}} !", null, null, 1)).isEqualTo("Merci !");
        assertThat(render("Offre de rentrée : -20% cette semaine.", "Ali", null, 1))
                .isEqualTo("Offre de rentrée : -20% cette semaine.");
    }

    @Test
    void personalizedMeansAPlaceholderOrVariants() {
        assertThat(isPersonalized("Bonjour {{first_name}}")).isTrue();
        assertThat(isPersonalized("{Bonjour|Salut}, une question")).isTrue();
        assertThat(isPersonalized("Bonjour à tous {2026}")).isFalse();
        assertThat(isPersonalized("Offre de rentrée")).isFalse();
    }
}
