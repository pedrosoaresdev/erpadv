export interface Option { value: string; label: string }
export interface LgpdOptions { legalBases: Option[]; requestTypes: Option[]; documentCategories: Option[] }
export interface Entry { at: string; byName: string; text: string }

export type RequestStatus = 'RECEBIDA' | 'EM_ANALISE' | 'ATENDIDA' | 'NEGADA' | 'CANCELADA';
export const REQUEST_STATUS: Record<RequestStatus, string> = {
  RECEBIDA: 'Recebida', EM_ANALISE: 'Em análise', ATENDIDA: 'Atendida', NEGADA: 'Negada', CANCELADA: 'Cancelada',
};

export interface DataRequest {
  id: string; protocol: string; type: string; requesterName: string; requesterEmail: string | null; requesterPhone: string | null;
  clientId: string | null; clientName: string | null; channel: string | null; description: string | null; receivedDate: string; dueDate: string;
  identityVerified: boolean; status: RequestStatus; response: string | null; respondedAt: string | null; respondedByName: string | null;
  createdByName: string; history: Entry[];
}

export interface Consent {
  id: string; clientId: string; clientName: string; purpose: string; channel: string | null; evidence: string | null; documentId: string | null;
  grantedDate: string; expiresDate: string | null; revoked: boolean; revokedDate: string | null; revocationNote: string | null; createdByName: string;
}

export interface ProcessingActivity {
  id: string; name: string; purpose: string; legalBasis: string; sensitiveData: boolean; sensitiveBasis: string | null;
  dataCategories: string[]; dataSubjects: string[]; sharedWith: string[]; retention: string | null; internationalTransfer: boolean;
  securityMeasures: string | null; responsibleName: string | null; active: boolean; suggestion: boolean;
}

export type IncidentRisk = 'BAIXO' | 'MEDIO' | 'ALTO';
export type IncidentStatus = 'ABERTO' | 'EM_TRATAMENTO' | 'ENCERRADO';
export interface Incident {
  id: string; title: string; description: string; discoveredAt: string; occurredAt: string | null; dataCategories: string[];
  affectedSubjects: number; sensitiveData: boolean; risk: IncidentRisk; anpdNotificationRequired: boolean; anpdDeadline: string | null;
  anpdNotifiedDate: string | null; anpdProtocol: string | null; subjectsNotifiedDate: string | null; containmentMeasures: string | null;
  status: IncidentStatus; createdByName: string; timeline: Entry[];
}

export interface DataShare {
  id: string; recipient: string; recipientType: string | null; clientId: string | null; clientName: string | null; caseId: string | null;
  dataDescription: string; purpose: string; legalBasis: string; sharedDate: string; createdByName: string;
}

export interface PrivacyPolicy {
  id: string; version: number; title: string; content: string; published: boolean; publishedAt: string | null; publishedByName: string | null;
  createdByName: string; updatedAt: string;
}

export interface RetentionRule { id: string; category: string; years: number; legalReference: string | null }
export interface RetentionItem { documentId: string; title: string; category: string; clientName: string | null; caseNumber: string | null;
  createdAt: string; expiredOn: string }

export interface LgpdOverview {
  openRequests: number; overdueRequests: number; openIncidents: number; anpdPending: number; activeConsents: number; activities: number;
  pendingSuggestions: number; publishedPolicyVersion: number | null; nextRequestDue: string | null;
}

export function splitList(text: string): string[] {
  return text.split(/[\n;,]/).map((s) => s.trim()).filter(Boolean);
}
