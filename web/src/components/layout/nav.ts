import type { ComponentType, SVGProps } from 'react';
import {
  IconAlerts,
  IconControls,
  IconFinder,
  IconMessages,
  IconNetwork,
  IconOverview,
  IconPower,
  IconSettings,
  IconSignal,
  IconSystem,
} from '../ui/icons';

export interface NavItem {
  to: string;
  label: string;
  short?: string;
  icon: ComponentType<SVGProps<SVGSVGElement>>;
  /** Plan 9: viewers see everything except Controls, root shell and Messages. */
  adminOnly?: boolean;
}

export const NAV: NavItem[] = [
  { to: '/', label: 'Overview', icon: IconOverview },
  { to: '/signal', label: 'Signal', icon: IconSignal },
  { to: '/network', label: 'Network', icon: IconNetwork },
  { to: '/power', label: 'Power and heat', short: 'Power', icon: IconPower },
  { to: '/controls', label: 'Controls', icon: IconControls, adminOnly: true },
  { to: '/system', label: 'System', icon: IconSystem },
  { to: '/finder', label: 'Signal Finder', short: 'Finder', icon: IconFinder },
  { to: '/messages', label: 'Messages', icon: IconMessages, adminOnly: true },
  { to: '/alerts', label: 'Alerts', icon: IconAlerts },
  { to: '/settings', label: 'Settings', icon: IconSettings },
];

export function navFor(isAdmin: boolean): NavItem[] {
  return NAV.filter((n) => isAdmin || !n.adminOnly);
}

/** The four tabs on the phone's bottom bar; everything else is under "More". */
export function bottomTabs(isAdmin: boolean): NavItem[] {
  const pick = isAdmin ? ['/', '/signal', '/finder', '/controls'] : ['/', '/signal', '/finder', '/network'];
  return pick.map((p) => NAV.find((n) => n.to === p)!).filter(Boolean);
}
