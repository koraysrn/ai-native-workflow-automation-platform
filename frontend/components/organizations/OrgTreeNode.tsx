"use client";

import { useState } from "react";
import type { CompanyTreeDto, DepartmentTreeDto, TeamTreeDto } from "../../lib/api";

function TeamNode({ team }: { team: TeamTreeDto }) {
  const [open, setOpen] = useState(false);
  return (
    <li>
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        style={{ background: "none", border: "none", cursor: "pointer" }}
      >
        {open ? "▾" : "▸"} {team.name}
      </button>
      {open && (
        <ul>
          {team.users.map((user) => (
            <li key={user.id}>
              {user.fullName} — {user.email}
            </li>
          ))}
        </ul>
      )}
    </li>
  );
}

function DepartmentNode({ department }: { department: DepartmentTreeDto }) {
  const [open, setOpen] = useState(false);
  return (
    <li>
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        style={{ background: "none", border: "none", cursor: "pointer" }}
      >
        {open ? "▾" : "▸"} {department.name}
      </button>
      {open && (
        <ul>
          {department.teams.map((team) => (
            <TeamNode key={team.id} team={team} />
          ))}
        </ul>
      )}
    </li>
  );
}

function CompanyNode({ company }: { company: CompanyTreeDto }) {
  const [open, setOpen] = useState(false);
  return (
    <li>
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        style={{ background: "none", border: "none", cursor: "pointer" }}
      >
        {open ? "▾" : "▸"} {company.name}
      </button>
      {open && (
        <ul>
          {company.departments.map((department) => (
            <DepartmentNode key={department.id} department={department} />
          ))}
        </ul>
      )}
    </li>
  );
}

export default function OrgTreeNode({ companies }: { companies: CompanyTreeDto[] }) {
  return (
    <ul>
      {companies.map((company) => (
        <CompanyNode key={company.id} company={company} />
      ))}
    </ul>
  );
}
