package com.awap.organization.interfaces.dto;

import com.awap.organization.domain.Company;
import com.awap.organization.domain.Department;
import com.awap.organization.domain.OrganizationUser;
import com.awap.organization.domain.Position;
import com.awap.organization.domain.Team;
import org.mapstruct.Mapper;

/** Maps organization entities to their public representations. */
@Mapper(componentModel = "spring")
public interface OrganizationMapper {

  CompanyResponse toResponse(Company company);

  DepartmentResponse toResponse(Department department);

  TeamResponse toResponse(Team team);

  PositionResponse toResponse(Position position);

  OrgUserResponse toResponse(OrganizationUser user);
}
