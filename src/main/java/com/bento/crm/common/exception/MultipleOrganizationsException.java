package com.bento.crm.common.exception;

import com.bento.crm.auth.dto.OrganizationChoiceDto;
import lombok.Getter;

import java.util.List;

@Getter
public class MultipleOrganizationsException extends RuntimeException {

    private final List<OrganizationChoiceDto> organizations;

    public MultipleOrganizationsException(String message, List<OrganizationChoiceDto> organizations) {
        super(message);
        this.organizations = organizations;
    }
}
