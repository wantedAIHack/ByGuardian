export type Diagnosis = 'STROKE' | 'OTHER' | 'UNKNOWN';
export type PareticSide = 'LEFT' | 'RIGHT' | 'NONE' | 'UNKNOWN';
export type VerbalDifficulty = 'NONE' | 'SOMETIMES' | 'OFTEN';
export type Axis = 'LEVEL' | 'AID' | 'CONSISTENCY' | 'HAND';
export type SnapshotKind = 'BASELINE' | 'WEEKLY' | 'FULL_RECHECK';

export interface CodeLabel { code: string; label: string }
export interface CatalogValue { value: number; label: string }
export interface CatalogItem {
  code: string; label: string; phrase: string; group: string; axes: Axis[];
}
export interface Catalog {
  set: string;
  items: CatalogItem[];
  axes: Record<string, CatalogValue[]>;
  signalActions: CodeLabel[];
  signalKinds: CodeLabel[];
  timeTags: CodeLabel[];
  sleepLevels: CodeLabel[];
  axisLabels: CodeLabel[];
  axisQuestions: CodeLabel[];
}

export interface ItemInput {
  level: number | null;
  aid: number | null;
  consistency: number | null;
  hand: number | null;
  note: string | null;
}
export interface FreeNoteInput { text: string; timeTag: string | null }

export interface OnboardingRequest {
  relation: string;
  diagnosis: Diagnosis;
  pareticSide: PareticSide;
  verbalDifficulty: VerbalDifficulty;
  nextVisitDate: string | null;
  baseline: {
    items: Record<string, ItemInput>;
    painSignal: Record<string, string[]> | null;
    sleep: number | null;
    freeNote: FreeNoteInput | null;
  };
}
export interface OnboardingResponse {
  caseId: string; guardianToken: string; recoveryCode: string; week: number;
}

export interface Me {
  caseId: string;
  relation: string;
  today: string;
  week: number;
  fullRecheck: boolean;
  signalsEnabled: boolean;
  handEnabled: boolean;
  canRecordThisWeek: boolean;
  recordedThisWeek: boolean;
  lastRecordedWeek: number | null;
  nextVisitDate: string | null;
  recordedWeeks: number;
  totalWeeks: number;
}

export interface RecoverRequest { recoveryCode: string; relation: string }
export interface RecoverResponse { guardianToken: string; caseId: string }
export interface RecoveryCodeResponse { recoveryCode: string }

export interface WeeklyRecordRequest {
  noChange: boolean;
  changedItems: Record<string, ItemInput>;
  painSignal: Record<string, string[]> | null;
  sleep: number | null;
  freeNote: FreeNoteInput | null;
}
export interface WeeklyRecordResponse {
  week: number; kind: SnapshotKind; questionsRefreshed: boolean;
}

export interface Change {
  item: string; label: string; axis: string; axisLabel: string;
  status: string; duration: number; from: string; to: string; message: string;
}
export interface Transition { item: string; label: string; axis: string; message: string }
export interface ProgressQuestion { rank: number; type: string; sentence: string; source: string }
export interface Progress {
  week: number; silent: boolean;
  changes: Change[]; transitions: Transition[]; questions: ProgressQuestion[];
}

export interface Point { week: number; value: number; label: string; source: string }
export interface AxisSeries { axis: string; axisLabel: string; values: Point[] }
export interface Trajectory { code: string; label: string; changed: boolean; axes: AxisSeries[] }

export interface EvidenceItem {
  code: string; label: string; axis: string; axisLabel: string; values: Point[];
}
export interface SignalEvidence {
  action: string; actionLabel: string; kind: string; kindLabel: string;
  weeks: number[]; window: number;
}
export interface Evidence { items: EvidenceItem[]; signal: SignalEvidence | null }
export interface PrepQuestion {
  rank: number; type: string; sentence: string; source: string; evidence: Evidence;
}
export type QuestionOrigin = 'TEMPLATE' | 'LLM' | 'CAREGIVER';
export type GenerationStatus = 'PENDING' | 'DONE' | 'FAILED' | 'TEMPLATE_ONLY';
export interface NoteBasis {
  week: number; timeTagLabel: string | null; itemLabel: string | null; text: string;
}
export interface ItemBasis { evidence: Evidence; notes: NoteBasis[] }
export interface PrepItem {
  id: string; sentence: string; origin: QuestionOrigin; edited: boolean; basis: ItemBasis;
}
export interface PrepCard {
  week: number;
  nextVisitDate: string | null;
  questions: PrepQuestion[];
  extraQuestions: string[];
  emptyMessage: string | null;
  therapistGlance: string[];
  /** 새 백엔드만 보낸다. 없으면 옛 필드로 읽기 전용 화면을 그린다. */
  items?: PrepItem[];
  generationStatus?: GenerationStatus;
  edited?: boolean;
  suggestionAvailable?: boolean;
}

export interface TherapistLink { url: string; token: string }

export interface TherapistSignal {
  action: string; actionLabel: string; kind: string; kindLabel: string; weeks: number[];
}
export interface TherapistSleep { week: number; value: number; label: string }
export interface TherapistFreeNote {
  week: number; text: string; timeTag: string | null; timeTagLabel: string | null;
}
export interface Density {
  totalWeeks: number; recordedWeeks: number; confirmedWeeks: number; authors: string[];
}
export interface AuthorChange { week: number; from: string; to: string }
export interface TherapistQuestionDetail {
  sentence: string; origin: QuestionOrigin; noteWeeks: number[];
}
export interface TherapistSummary {
  generatedAt: string;
  weeks: number[];
  items: Trajectory[];
  signals: TherapistSignal[];
  sleep: TherapistSleep[];
  signalsEnabled: boolean;
  freeNotes: TherapistFreeNote[];
  questions: string[];
  extraQuestions: string[];
  density: Density;
  authorChanges: AuthorChange[];
  questionDetails?: TherapistQuestionDetail[];
  disclaimer: string;
}

export interface DemoResponse {
  caseId: string; guardianToken: string; recoveryCode: string; therapistUrl: string; therapistToken: string;
}
export interface ApiErrorBody { code: string; message: string }
