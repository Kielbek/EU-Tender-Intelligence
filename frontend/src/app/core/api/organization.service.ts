import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type OrganizationRole = 'COMPANY_ADMIN' | 'TEAM_MEMBER';

export interface Membership {
  id: string;
  name: string;
  slug: string;
  planCode: string;
  role: OrganizationRole;
}

export interface Organization {
  id: string;
  name: string;
  slug: string;
  countryCode?: string;
  vatId?: string;
  billingEmail?: string;
  planCode: string;
  status: string;
  createdAt: string;
}

export interface Member {
  userId: string;
  email: string;
  fullName: string;
  role: OrganizationRole;
  joinedAt: string;
}

export interface Invitation {
  id: string;
  email: string;
  role: OrganizationRole;
  expiresAt: string;
  createdAt: string;
}

export interface UpdateOrganization {
  name?: string;
  countryCode?: string;
  vatId?: string;
  billingEmail?: string;
}

@Injectable({ providedIn: 'root' })
export class OrganizationService {
  private readonly http = inject(HttpClient);

  mine(): Observable<Membership[]> {
    return this.http.get<Membership[]>('/api/v1/organizations');
  }

  get(id: string): Observable<Organization> {
    return this.http.get<Organization>(`/api/v1/organizations/${id}`);
  }

  update(id: string, changes: UpdateOrganization): Observable<Organization> {
    return this.http.patch<Organization>(`/api/v1/organizations/${id}`, changes);
  }

  members(id: string): Observable<Member[]> {
    return this.http.get<Member[]>(`/api/v1/organizations/${id}/members`);
  }

  changeRole(id: string, userId: string, role: OrganizationRole): Observable<void> {
    return this.http.put<void>(`/api/v1/organizations/${id}/members/${userId}/role`, { role });
  }

  removeMember(id: string, userId: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/organizations/${id}/members/${userId}`);
  }

  invite(id: string, email: string, role: OrganizationRole): Observable<Invitation> {
    return this.http.post<Invitation>(`/api/v1/organizations/${id}/invitations`, { email, role });
  }

  invitations(id: string): Observable<Invitation[]> {
    return this.http.get<Invitation[]>(`/api/v1/organizations/${id}/invitations`);
  }

  revokeInvitation(id: string, invitationId: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/organizations/${id}/invitations/${invitationId}`);
  }

  acceptInvitation(token: string): Observable<Membership> {
    return this.http.post<Membership>('/api/v1/invitations/accept', { token });
  }
}
