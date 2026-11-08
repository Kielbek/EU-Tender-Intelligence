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
}

/** Fit indicator 0–100 between the company profile and a tender – never a probability of winning. */
export interface MatchView {
  score: number;
  breakdown: MatchBreakdown;
  tender: TenderSummary;
}
