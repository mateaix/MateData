import {
  Aim,
  ArrowLeft,
  ArrowRight,
  ChatDotRound,
  Clock,
  Coin,
  Collection,
  Connection,
  Cpu,
  DataLine,
  Edit,
  Histogram,
  Loading,
  Lock,
  MagicStick,
  PieChart,
  Plus,
  Promotion,
  SwitchButton,
  TopRight,
  TrendCharts,
  UserFilled,
} from "@element-plus/icons-vue";

/**
 * The only place the UI chooses icons. Templates refer to these semantic names
 * through <AppIcon>; never use emoji, symbol characters or hand-written SVG as icons.
 */
export const icons = {
  // Navigation: one icon per workspace page.
  ask: MagicStick,
  sources: Connection,
  datasets: Collection,
  runs: Clock,
  evaluations: Aim,
  settings: Cpu,
  governance: UserFilled,
  // Domain objects and assurances.
  database: Coin,
  readOnly: Lock,
  conversation: ChatDotRound,
  // Suggested analyses.
  trend: TrendCharts,
  share: PieChart,
  series: DataLine,
  distribution: Histogram,
  // Actions.
  add: Plus,
  send: Promotion,
  forward: ArrowRight,
  back: ArrowLeft,
  open: TopRight,
  edit: Edit,
  logout: SwitchButton,
  loading: Loading,
} as const;

export type IconName = keyof typeof icons;
