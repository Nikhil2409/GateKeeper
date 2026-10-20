import { useLayoutEffect, useRef, useState } from 'react';
import { CircleAlert, TriangleAlert } from 'lucide-react';

/* ---------- number formatting ---------- */

// 1,284 / 12.9K / 4.2M: compact from 10,000 up, so mid-size counts stay exact
export function compact(value) {
  if (value == null || Number.isNaN(value)) return '—';
  const abs = Math.abs(value);
  const short = (n, suffix) => `${(value / n).toFixed(1).replace(/\.0$/, '')}${suffix}`;
  if (abs >= 1e9) return short(1e9, 'B');
  if (abs >= 1e6) return short(1e6, 'M');
  if (abs >= 1e4) return short(1e3, 'K');
  return Math.round(value).toLocaleString();
}

export const formatPercent = (value, digits = 1) => (value == null ? '—' : `${(value * 100).toFixed(digits)}%`);
export const formatMs = (value) => (value == null ? '—' : `${compact(value)} ms`);

/* ---------- layout helpers ---------- */

function useWidth() {
  const ref = useRef(null);
  const [width, setWidth] = useState(0);
  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return undefined;
    setWidth(element.clientWidth);
    const observer = new ResizeObserver(([entry]) => setWidth(Math.round(entry.contentRect.width)));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  return [ref, width];
}

// Clean axis ticks: 0, step, 2·step … with step from the 1-2-2.5-5-10 family
function niceScale(max, tickCount = 4) {
  const safeMax = max > 0 ? max : 1;
  const raw = safeMax / tickCount;
  const power = 10 ** Math.floor(Math.log10(raw));
  const fraction = raw / power;
  const step = (fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 2.5 ? 2.5 : fraction <= 5 ? 5 : 10) * power;
  const top = Math.ceil(safeMax / step) * step;
  const ticks = [];
  for (let value = 0; value <= top + step / 1e6; value += step) ticks.push(value);
  return { top, ticks };
}

function pickTickIndexes(count, maxTicks) {
  if (count <= maxTicks) return [...Array(count).keys()];
  const step = (count - 1) / (maxTicks - 1);
  return [...Array(maxTicks).keys()].map((i) => Math.round(i * step));
}

const MARGIN = { top: 12, right: 16, bottom: 26, left: 48 };

function Tooltip({ visible, x, containerWidth, title, rows }) {
  if (!visible) return null;
  const flip = x > containerWidth - 190;
  return (
    <div className="chart-tooltip" style={{ left: flip ? undefined : x + 12, right: flip ? containerWidth - x + 12 : undefined }}>
      <div className="tt-title">{title}</div>
      {rows.map((row) => (
        <div key={row.label} className="tt-row">
          {row.color && <span className="tt-key" style={{ background: row.color }} />}
          <span className="tt-value">{row.value}</span>
          <span className="tt-label">{row.label}</span>
        </div>
      ))}
    </div>
  );
}

function YAxis({ ticks, y, width, format }) {
  return ticks.map((tick) => (
    <g key={tick}>
      <line x1={MARGIN.left} x2={width - MARGIN.right} y1={y(tick)} y2={y(tick)} className="chart-grid" />
      <text x={MARGIN.left - 8} y={y(tick)} dy="0.32em" textAnchor="end">{format(tick)}</text>
    </g>
  ));
}

/* ---------- line chart: trend over time, crosshair + one tooltip for every series ---------- */

export function LineChart({ data, xKey, xFormat, tooltipTitle = xFormat, series, yFormat = compact, height = 220 }) {
  const [ref, width] = useWidth();
  const [hover, setHover] = useState(null);
  const endLabelSpace = series.length > 1 ? 56 : 0;
  const plotRight = width - MARGIN.right - endLabelSpace;
  const plotHeight = height - MARGIN.top - MARGIN.bottom;

  const max = Math.max(...data.flatMap((row) => series.map((s) => row[s.key] ?? 0)));
  const { top, ticks } = niceScale(max);
  const x = (i) => MARGIN.left + (data.length <= 1 ? 0 : (i / (data.length - 1)) * (plotRight - MARGIN.left));
  const y = (value) => MARGIN.top + plotHeight - (value / top) * plotHeight;

  const lastIndex = data.length - 1;
  const endYs = series.map((s) => y(data[lastIndex]?.[s.key] ?? 0));
  // Direct end labels only while they don't collide; otherwise the legend and tooltip carry identity
  const labelsFit = series.length <= 4
    && endYs.every((a, i) => endYs.every((b, j) => i === j || Math.abs(a - b) >= 14));

  function onPointerMove(event) {
    const bounds = event.currentTarget.getBoundingClientRect();
    const px = event.clientX - bounds.left;
    const ratio = (px - MARGIN.left) / Math.max(plotRight - MARGIN.left, 1);
    setHover(Math.min(lastIndex, Math.max(0, Math.round(ratio * lastIndex))));
  }

  return (
    <div className="chart" ref={ref}>
      {series.length > 1 && (
        <div className="chart-legend">
          {series.map((s) => <span key={s.key}><span className="legend-key" style={{ background: s.color }} />{s.label}</span>)}
        </div>
      )}
      {width > 0 && data.length > 0 && (
        <svg width={width} height={height} role="img" aria-label={series.map((s) => s.label).join(', ')}>
          <YAxis ticks={ticks} y={y} width={plotRight + MARGIN.right} format={yFormat} />
          <line x1={MARGIN.left} x2={plotRight} y1={y(0)} y2={y(0)} className="chart-axis" />
          {pickTickIndexes(data.length, Math.max(2, Math.floor((plotRight - MARGIN.left) / 90))).map((i) => (
            <text key={i} x={x(i)} y={height - 6} textAnchor={i === 0 ? 'start' : i === lastIndex ? 'end' : 'middle'}>
              {xFormat(data[i][xKey])}
            </text>
          ))}

          {series.length === 1 && (
            <path
              d={`M${x(0)},${y(0)} ${data.map((row, i) => `L${x(i)},${y(row[series[0].key] ?? 0)}`).join(' ')} L${x(lastIndex)},${y(0)} Z`}
              fill={series[0].color}
              opacity="0.1"
            />
          )}
          {series.map((s) => (
            <path
              key={s.key}
              d={data.map((row, i) => `${i === 0 ? 'M' : 'L'}${x(i)},${y(row[s.key] ?? 0)}`).join(' ')}
              fill="none"
              stroke={s.color}
              strokeWidth="2"
              strokeLinejoin="round"
              strokeLinecap="round"
            />
          ))}
          {series.map((s, i) => (
            <g key={s.key}>
              <circle cx={x(lastIndex)} cy={endYs[i]} r="4" fill={s.color} className="chart-ring" />
              {labelsFit && series.length > 1 && (
                <text x={x(lastIndex) + 10} y={endYs[i]} dy="0.32em" className="chart-end-label">
                  {yFormat(data[lastIndex][s.key])}
                </text>
              )}
            </g>
          ))}

          {hover !== null && (
            <g>
              <line x1={x(hover)} x2={x(hover)} y1={MARGIN.top} y2={y(0)} className="chart-crosshair" />
              {series.map((s) => (
                <circle key={s.key} cx={x(hover)} cy={y(data[hover][s.key] ?? 0)} r="4" fill={s.color} className="chart-ring" />
              ))}
            </g>
          )}
          <rect
            x={MARGIN.left}
            y={0}
            width={Math.max(plotRight - MARGIN.left, 0)}
            height={height}
            fill="transparent"
            onPointerMove={onPointerMove}
            onPointerLeave={() => setHover(null)}
          />
        </svg>
      )}
      <Tooltip
        visible={hover !== null}
        x={hover !== null ? x(hover) : 0}
        containerWidth={width}
        title={hover !== null ? tooltipTitle(data[hover][xKey]) : ''}
        rows={hover !== null ? series.map((s) => ({ label: s.label, value: yFormat(data[hover][s.key]), color: s.color })) : []}
      />
    </div>
  );
}

/* ---------- column chart: one series over time, each bar is its own hover target ---------- */

function topRoundedBar(x, y, width, height, radius) {
  const r = Math.min(radius, width / 2, height);
  return `M${x},${y + height} V${y + r} Q${x},${y} ${x + r},${y} H${x + width - r} Q${x + width},${y} ${x + width},${y + r} V${y + height} Z`;
}

export function ColumnChart({ data, xKey, xFormat, tooltipTitle = xFormat, yKey, color, yFormat = compact, tooltipRows, height = 220 }) {
  const [ref, width] = useWidth();
  const [hover, setHover] = useState(null);
  const plotRight = width - MARGIN.right;
  const plotHeight = height - MARGIN.top - MARGIN.bottom;
  const { top, ticks } = niceScale(Math.max(...data.map((row) => row[yKey] ?? 0)));
  const band = (plotRight - MARGIN.left) / Math.max(data.length, 1);
  const barWidth = Math.max(2, Math.min(24, band - 2));
  const y = (value) => MARGIN.top + plotHeight - (value / top) * plotHeight;

  return (
    <div className="chart" ref={ref}>
      {width > 0 && data.length > 0 && (
        <svg width={width} height={height} role="img" aria-label={yKey}>
          <YAxis ticks={ticks} y={y} width={width} format={yFormat} />
          {data.map((row, i) => {
            const value = row[yKey] ?? 0;
            const barX = MARGIN.left + i * band + (band - barWidth) / 2;
            return (
              <g key={row[xKey]}>
                <path
                  d={topRoundedBar(barX, y(value), barWidth, y(0) - y(value), 4)}
                  fill={color}
                  opacity={hover === null || hover === i ? 1 : 0.55}
                />
                {/* hit target: the whole band and plot height, much bigger than the painted bar */}
                <rect
                  x={MARGIN.left + i * band}
                  y={MARGIN.top}
                  width={band}
                  height={plotHeight}
                  fill="transparent"
                  onPointerEnter={() => setHover(i)}
                  onPointerLeave={() => setHover(null)}
                />
              </g>
            );
          })}
          <line x1={MARGIN.left} x2={plotRight} y1={y(0)} y2={y(0)} className="chart-axis" />
          {pickTickIndexes(data.length, Math.max(2, Math.floor((plotRight - MARGIN.left) / 90))).map((i) => (
            <text key={i} x={MARGIN.left + i * band + band / 2} y={height - 6} textAnchor="middle">
              {xFormat(data[i][xKey])}
            </text>
          ))}
        </svg>
      )}
      <Tooltip
        visible={hover !== null}
        x={hover !== null ? MARGIN.left + hover * band + band / 2 : 0}
        containerWidth={width}
        title={hover !== null ? tooltipTitle(data[hover][xKey]) : ''}
        rows={hover !== null ? (tooltipRows ? tooltipRows(data[hover]) : [{ label: yKey, value: yFormat(data[hover][yKey]), color }]) : []}
      />
    </div>
  );
}

/* ---------- horizontal bar list: compare magnitude across named items, value at the tip ---------- */

export function BarList({ items, format = compact, color = 'var(--series-1)' }) {
  const max = Math.max(...items.map((item) => item.value), 1);
  return (
    <ul className="bar-list">
      {items.map((item) => (
        <li key={item.label} title={`${item.label}: ${format(item.value)}`}>
          <div className="bar-list-label">
            <span>{item.label}</span>
            {item.sub && <span className="muted">{item.sub}</span>}
          </div>
          <div className="bar-list-track">
            <div className="bar-list-bar" style={{ width: `${(item.value / max) * 100}%`, background: color }} />
            <span className="bar-list-value">{format(item.value)}</span>
          </div>
        </li>
      ))}
    </ul>
  );
}

/* ---------- sparkline: 12+ points in the de-emphasis hue, current value in the accent ---------- */

export function Sparkline({ values, height = 28 }) {
  if (!values?.length) return null;
  const max = Math.max(...values);
  const min = Math.min(...values);
  const range = max - min || 1;
  const width = 100;
  const point = (value, i) => [(i / (values.length - 1)) * width, height - 3 - ((value - min) / range) * (height - 6)];
  const [lastX, lastY] = point(values[values.length - 1], values.length - 1);
  return (
    <svg className="sparkline" viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" aria-hidden="true">
      <path
        d={values.map((value, i) => `${i === 0 ? 'M' : 'L'}${point(value, i).join(',')}`).join(' ')}
        fill="none"
        stroke="var(--chart-deemph)"
        strokeWidth="1.5"
        vectorEffect="non-scaling-stroke"
      />
      <circle cx={lastX} cy={lastY} r="2.5" fill="var(--series-1)" />
    </svg>
  );
}

/* ---------- stat tile: label · value · delta vs previous period · optional trend ---------- */

export function StatTile({ label, value, delta, upIsGood = true, deltaLabel = 'vs previous period', trend, icon: Icon }) {
  const direction = delta == null || delta === 0 ? 'flat' : delta > 0 ? 'up' : 'down';
  // upIsGood = null for neutral metrics: the delta is shown but not colored
  const good = direction === 'flat' || upIsGood === null ? null : (direction === 'up') === upIsGood;
  return (
    <div className="card stat-tile">
      <div className="stat-tile-head">
        <span className="stat-label">{label}</span>
        {Icon && <Icon size={16} className="muted" />}
      </div>
      <strong className="stat-tile-value">{value}</strong>
      {delta != null && (
        <span className={`stat-delta ${good === null ? '' : good ? 'delta-good' : 'delta-bad'}`}>
          {direction === 'up' ? '▲' : direction === 'down' ? '▼' : '•'} {formatPercent(Math.abs(delta))}
          <span className="muted"> {deltaLabel}</span>
        </span>
      )}
      {trend && <Sparkline values={trend} />}
    </div>
  );
}

/* ---------- meter: a ratio against a limit, severity carried by the fill plus an icon + label ---------- */

export function Meter({ value, max }) {
  const ratio = max > 0 ? Math.min(value / max, 1) : 0;
  const level = ratio >= 1 ? 'critical' : ratio >= 0.85 ? 'warning' : 'normal';
  return (
    <div className="meter-wrap">
      <div className={`meter meter-${level}`} role="meter" aria-valuenow={value} aria-valuemin={0} aria-valuemax={max}>
        <div className="meter-fill" style={{ width: `${ratio * 100}%` }} />
      </div>
      <span className="meter-text">
        {compact(value)} / {compact(max)}
        {level === 'critical' && <span className="meter-flag flag-critical"><CircleAlert size={13} /> At limit</span>}
        {level === 'warning' && <span className="meter-flag flag-warning"><TriangleAlert size={13} /> Near limit</span>}
      </span>
    </div>
  );
}

/* ---------- chart card: every chart has a table-view twin ---------- */

export function ChartCard({ title, subtitle, loading = false, table, children }) {
  const [view, setView] = useState('chart');
  return (
    <section className="card chart-card">
      <div className="chart-card-header">
        <div>
          <h2>{title}</h2>
          {subtitle && <p className="muted small">{subtitle}</p>}
        </div>
        {table && (
          <div className="segmented segmented-sm">
            <button className={view === 'chart' ? 'active' : ''} onClick={() => setView('chart')}>Chart</button>
            <button className={view === 'table' ? 'active' : ''} onClick={() => setView('table')}>Table</button>
          </div>
        )}
      </div>
      {/* refetch keeps the frame: previous render stays, dimmed, no skeleton flash */}
      <div style={{ opacity: loading ? 0.5 : 1, transition: 'opacity 0.15s' }}>
        {view === 'table' && table ? <DataTable {...table} /> : children}
      </div>
    </section>
  );
}

export function DataTable({ columns, rows, maxHeight = 320 }) {
  return (
    <div className="table-scroll" style={{ maxHeight }}>
      <table className="table table-compact">
        <thead>
          <tr>{columns.map((column) => <th key={column.label} className={column.numeric ? 'num' : ''}>{column.label}</th>)}</tr>
        </thead>
        <tbody>
          {rows.map((row, i) => (
            <tr key={i}>
              {columns.map((column) => (
                <td key={column.label} className={column.numeric ? 'num' : ''}>{column.render(row)}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
