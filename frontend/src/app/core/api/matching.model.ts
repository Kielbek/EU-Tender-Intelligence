import { TenderSummary } from './tender.model';

/** A stable reason code (translated as `match.reason.<code>`) with parameters such as a CPV code or a country. */
export interface MatchReason {
  code: string;
  params: Record<string, string | number>;
}

export type MatchComponentKey = 'cpv' | 'location' | 'value' | 'contractType' | 'experience';

export interface MatchComponent {
  key: MatchComponentKey;
  points: number;
  max: number;
}

export interface MatchBreakdown {
  score: number;
  components: MatchComponent[];
  met: MatchReason[];
  unmet: MatchReason[];
  missing: MatchReason[];
  /** A hard rule of the profile rules the tender out (excluded word or CPV, outside the area in strict mode). */
  excluded?: boolean;
  /** Distance between the company's base and the place of the tender (NUTS 3 label points), km. */
  distanceKm?: number | null;
}

/** Fit indicator 0–100 between the company profile and a tender – never a probability of winning. */
export interface MatchView {
  score: number;
  breakdown: MatchBreakdown;
  tender: TenderSummary;
}
