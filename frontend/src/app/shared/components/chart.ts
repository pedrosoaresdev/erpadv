import { AfterViewInit, Component, ElementRef, OnDestroy, effect, input, viewChild } from '@angular/core';
import {
  ArcElement,
  BarController,
  BarElement,
  CategoryScale,
  Chart,
  ChartConfiguration,
  DoughnutController,
  Filler,
  Legend,
  LineController,
  LineElement,
  LinearScale,
  PointElement,
  Tooltip,
} from 'chart.js';

Chart.register(
  ArcElement, BarController, BarElement, CategoryScale, DoughnutController, Filler, Legend,
  LineController, LineElement, LinearScale, PointElement, Tooltip,
);

export const CHART_COLORS = ['#12305a', '#c5a46d', '#3b6fb6', '#7a8ca8', '#2f855a', '#b7791f', '#6b46c1', '#c05621', '#2c7a7b', '#9b2c2c', '#4a5568'];

/** Wrapper fino do Chart.js com cores do tema e atualização reativa. */
@Component({
  selector: 'app-chart',
  template: `<div class="chart-box" [style.height.px]="height()"><canvas #canvas></canvas></div>`,
  styles: `.chart-box { position: relative; width: 100%; }`,
})
export class ChartComponent implements AfterViewInit, OnDestroy {
  readonly config = input.required<ChartConfiguration>();
  readonly height = input(260);
  private readonly canvas = viewChild.required<ElementRef<HTMLCanvasElement>>('canvas');
  private chart: Chart | null = null;

  constructor() {
    effect(() => {
      const config = this.config();
      if (this.chart) {
        this.chart.data = config.data;
        this.chart.options = this.withTheme(config).options ?? {};
        this.chart.update();
      }
    });
  }

  ngAfterViewInit(): void {
    this.chart = new Chart(this.canvas().nativeElement, this.withTheme(this.config()));
  }

  ngOnDestroy(): void {
    this.chart?.destroy();
  }

  private withTheme(config: ChartConfiguration): ChartConfiguration {
    const styles = getComputedStyle(document.documentElement);
    const text = styles.getPropertyValue('--app-text-muted').trim() || '#5d6778';
    const grid = styles.getPropertyValue('--app-border').trim() || '#e3e7ee';
    Chart.defaults.color = text;
    Chart.defaults.font.family = 'Inter, sans-serif';
    Chart.defaults.borderColor = grid;
    const options = config.options ?? {};
    return {
      ...config,
      options: {
        responsive: true,
        maintainAspectRatio: false,
        ...options,
        plugins: {
          legend: { labels: { usePointStyle: true, boxWidth: 8, padding: 14 } },
          tooltip: { backgroundColor: '#0b1f3a', padding: 10, cornerRadius: 8, titleColor: '#fff', bodyColor: '#e7eaf0' },
          ...(options.plugins ?? {}),
        },
      },
    } as ChartConfiguration;
  }
}
