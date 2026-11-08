import { MeetingPlatform } from '../../core/models/models';

export interface PlatformInfo {
  key: MeetingPlatform;
  label: string;
  icon: string;
  /** Página oficial para criar a sala (abre na conta do próprio advogado). */
  createUrl: string | null;
  createLabel: string | null;
  hosts: RegExp | null;
}

export const MEETING_PLATFORMS: PlatformInfo[] = [
  { key: 'GOOGLE_MEET', label: 'Google Meet', icon: 'video_call', createUrl: 'https://meet.google.com/new', createLabel: 'Criar sala no Google Meet',
    hosts: /^meet\.google\.com$/ },
  { key: 'ZOOM', label: 'Zoom', icon: 'videocam', createUrl: 'https://zoom.us/meeting/schedule', createLabel: 'Agendar no Zoom',
    hosts: /(^|\.)zoom(gov)?\.(us|com)$/ },
  { key: 'TEAMS', label: 'Microsoft Teams', icon: 'groups', createUrl: null, createLabel: null, hosts: /^teams\.(microsoft|live)\.com$/ },
  { key: 'PRESENCIAL', label: 'Presencial', icon: 'meeting_room', createUrl: null, createLabel: null, hosts: null },
  { key: 'OUTRO', label: 'Outro link', icon: 'link', createUrl: null, createLabel: null, hosts: null },
];

export function platformInfo(key: MeetingPlatform | null | undefined): PlatformInfo | null {
  return MEETING_PLATFORMS.find((p) => p.key === key) ?? null;
}

/** O link pertence à plataforma escolhida? (presencial/outro aceitam qualquer https) */
export function linkMatches(platform: MeetingPlatform | null | undefined, url: string): boolean {
  const info = platformInfo(platform);
  if (!url || !info?.hosts) return true;
  try {
    return info.hosts.test(new URL(url).hostname.toLowerCase());
  } catch {
    return false;
  }
}

/**
 * Abre a sala ao lado do sistema (janela à direita), para o advogado acompanhar processo, cliente e ata
 * enquanto participa. Google Meet e Zoom não permitem ser incorporados dentro de outra página.
 */
export function openMeetingWindow(url: string): void {
  const w = Math.min(1100, Math.round(window.screen.availWidth * 0.6));
  const h = window.screen.availHeight;
  const left = window.screen.availWidth - w;
  const win = window.open(url, 'erpadv-reuniao', `popup=yes,width=${w},height=${h},left=${left},top=0,noopener=no`);
  if (win) {
    win.opener = null;
  } else {
    window.open(url, '_blank', 'noopener');
  }
}
