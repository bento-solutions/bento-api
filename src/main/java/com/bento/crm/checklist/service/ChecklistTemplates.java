package com.bento.crm.checklist.service;

import com.bento.crm.checklist.dto.ChecklistDtos;

import java.util.List;

/** Ready-made lists of what a Moroccan accounting firm usually asks its clients for. */
public final class ChecklistTemplates {

    private ChecklistTemplates() {}

    public static final List<ChecklistDtos.Template> ALL = List.of(
            new ChecklistDtos.Template("MONTHLY", "Pièces du mois", List.of(
                    "Relevés bancaires du mois",
                    "Factures de vente",
                    "Factures d'achat et de frais",
                    "Justificatifs de caisse",
                    "États de paie et bordereau CNSS")),
            new ChecklistDtos.Template("QUARTERLY_VAT", "Déclaration de TVA du trimestre", List.of(
                    "Factures de vente du trimestre",
                    "Factures d'achat du trimestre",
                    "Relevés bancaires du trimestre",
                    "Relevé des déductions")),
            new ChecklistDtos.Template("YEAR_END", "Clôture de l'exercice", List.of(
                    "Inventaire des stocks au dernier jour",
                    "Relevés bancaires de décembre et attestations de solde",
                    "Tableaux d'amortissement des emprunts",
                    "Factures d'immobilisations de l'année",
                    "Contrats de leasing",
                    "État des créances et des dettes",
                    "Procès-verbal d'assemblée de l'exercice précédent")),
            new ChecklistDtos.Template("ONBOARDING", "Ouverture du dossier", List.of(
                    "Statuts de la société",
                    "Certificat d'inscription au registre de commerce (modèle J)",
                    "Attestation d'identifiant fiscal (IF)",
                    "Attestation ICE",
                    "Attestation d'affiliation CNSS",
                    "CIN du gérant",
                    "Attestation RIB",
                    "Dernier bilan déposé")));

    public static ChecklistDtos.Template get(String key) {
        return ALL.stream().filter(t -> t.key().equalsIgnoreCase(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown checklist template: " + key));
    }
}
