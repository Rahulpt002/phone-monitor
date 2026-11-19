/**
 * Signal grades (plan 7.1) mapped to colours. The thresholds themselves come from @hsm/shared so
 * the Dashboard, Server and Agent agree; this module only decides how a grade looks.
 * A grade is never shown by colour alone: every use pairs it with its label.
 */
import { gradeRsrp, gradeRsrq, gradeScore, gradeSinr, type Grade } from '@hsm/shared';

export type GradedMetric = 'score' | 'rsrp' | 'sinr' | 'rsrq';

const GRADERS: Record<GradedMetric, (v: number) => Grade> = {
  score: gradeScore,
  rsrp: gradeRsrp,
  sinr: gradeSinr,
  rsrq: gradeRsrq,
};

export function gradeOf(metric: GradedMetric, v: number | null | undefined): Grade | null {
  if (v == null || !Number.isFinite(v)) return null;
  return GRADERS[metric](v);
}

export const GRADE_LABEL: Record<Grade, string> = {
  excellent: 'Excellent',
  good: 'Good',
  fair: 'Fair',
  poor: 'Poor',
};

/** Tailwind classes backed by the --grade-* tokens in index.css (theme-aware). */
export const GRADE_TEXT: Record<Grade, string> = {
  excellent: 'text-grade-excellent',
  good: 'text-grade-good',
  fair: 'text-grade-fair',
  poor: 'text-grade-poor',
};

export const GRADE_BG: Record<Grade, string> = {
  excellent: 'bg-grade-excellent',
  good: 'bg-grade-good',
  fair: 'bg-grade-fair',
  poor: 'bg-grade-poor',
};

export const GRADE_VAR: Record<Grade, string> = {
  excellent: '--grade-excellent',
  good: '--grade-good',
  fair: '--grade-fair',
  poor: '--grade-poor',
};

/** Small glyph so the grade survives colour blindness and greyscale screens. */
export const GRADE_ICON: Record<Grade, string> = {
  excellent: '▲',
  good: '▲',
  fair: '■',
  poor: '▼',
};

export function gradeTextClass(grade: Grade | null): string {
  return grade ? GRADE_TEXT[grade] : 'text-muted';
}

export function gradeBgClass(grade: Grade | null): string {
  return grade ? GRADE_BG[grade] : 'bg-surface-3';
}

/** Plan 7.1 table, for legends and help text. */
export const THRESHOLD_TABLE: Record<
  Exclude<GradedMetric, 'score'>,
  { label: string; unit: string; meaning: string; bands: Record<Grade, string>; lines: number[] }
> = {
  rsrp: {
    label: 'RSRP',
    unit: 'dBm',
    meaning: 'Signal strength',
    bands: { excellent: 'above -80', good: '-80 to -90', fair: '-90 to -100', poor: 'below -100' },
    lines: [-80, -90, -100],
  },
  sinr: {
    label: 'SINR',
    unit: 'dB',
    meaning: 'How clean the signal is. Best predictor of speed.',
    bands: { excellent: 'above 20', good: '13 to 20', fair: '0 to 13', poor: 'below 0' },
    lines: [20, 13, 0],
  },
  rsrq: {
    label: 'RSRQ',
    unit: 'dB',
    meaning: 'Quality including load and interference',
    bands: { excellent: 'above -10', good: '-10 to -15', fair: '-15 to -20', poor: 'below -20' },
    lines: [-10, -15, -20],
  },
};

export const SCORE_LINES = [75, 55, 35];

export const GRADES: Grade[] = ['excellent', 'good', 'fair', 'poor'];
