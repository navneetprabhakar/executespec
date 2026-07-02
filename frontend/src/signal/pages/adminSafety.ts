export type AdminWriteChange =
  | {
      kind: 'user-tier';
      email: string;
      from: string;
      to: string;
    }
  | {
      kind: 'user-role';
      email: string;
      from: string;
      to: string;
    }
  | {
      kind: 'user-status';
      email: string;
      from: string;
      to: string;
    }
  | {
      kind: 'org-tier';
      name: string;
      from: string;
      to: string;
    }
  | {
      kind: 'org-status';
      name: string;
      from: string;
      to: string;
    };

export type AdminWriteConfirmation = {
  title: string;
  body: string;
  confirmLabel: string;
  danger: boolean;
};

export function adminWriteConfirmation(change: AdminWriteChange): AdminWriteConfirmation {
  switch (change.kind) {
    case 'user-tier':
      return {
        title: 'Change user tier',
        body: `Change ${change.email} from ${change.from} to ${change.to}? This changes entitlement and quota behavior immediately.`,
        confirmLabel: 'Change tier',
        danger: false,
      };
    case 'user-role':
      return {
        title: 'Change user role',
        body: `Change ${change.email} role from ${change.from} to ${change.to}? SUPER_ADMIN can operate platform-wide admin surfaces.`,
        confirmLabel: 'Change role',
        danger: change.from === 'SUPER_ADMIN' || change.to === 'SUPER_ADMIN',
      };
    case 'user-status':
      return {
        title: change.to === 'SUSPENDED' ? 'Suspend user' : 'Change user status',
        body:
          change.to === 'SUSPENDED'
            ? `Suspend ${change.email}? They will lose access until reactivated.`
            : `Change ${change.email} status from ${change.from} to ${change.to}?`,
        confirmLabel: change.to === 'SUSPENDED' ? 'Suspend' : 'Change status',
        danger: change.to === 'SUSPENDED',
      };
    case 'org-tier':
      return {
        title: 'Change org tier',
        body: `Change ${change.name} from ${change.from} to ${change.to}? This changes workspace entitlement and billing behavior immediately.`,
        confirmLabel: 'Change tier',
        danger: false,
      };
    case 'org-status':
      return {
        title: change.to === 'SUSPENDED' ? 'Suspend org' : 'Change org status',
        body:
          change.to === 'SUSPENDED'
            ? `Suspend ${change.name}? All members lose access until reactivated.`
            : `Change ${change.name} status from ${change.from} to ${change.to}?`,
        confirmLabel: change.to === 'SUSPENDED' ? 'Suspend' : 'Change status',
        danger: change.to === 'SUSPENDED',
      };
    default:
      return assertNever(change);
  }
}

function assertNever(value: never): never {
  throw new Error(`Unsupported admin write change: ${JSON.stringify(value)}`);
}
