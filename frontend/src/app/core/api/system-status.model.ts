/** Mirrors eu.tenderintel.system.SystemStatus (GET /api/v1/system/status). */
export type Health = 'UP' | 'DOWN';
export type Readiness = 'READY' | 'PENDING_CONFIGURATION' | 'NOT_IMPLEMENTED';

export interface DependencyStatus {
  name: string;
  status: Health;
  detail?: string;
}

export interface IntegrationStatus {
  code: string;
  status: Readiness;
  configured: boolean;
  plannedStage: number;
}

export interface SystemStatus {
  application: {
    name: string;
    version: string;
    environment: string;
    defaultLocale: string;
  };
  dependencies: DependencyStatus[];
  integrations: IntegrationStatus[];
  checkedAt: string;
}
