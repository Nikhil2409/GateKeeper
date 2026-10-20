export default function Logo({ size = 36 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden="true" className="logo">
      <rect width="32" height="32" rx="9" fill="url(#logo-gradient)" />
      <path d="M10 9h12v4h-8v6h4v-2h-2v-3h6v9H10z" fill="white" />
      <defs>
        <linearGradient id="logo-gradient" x1="0" y1="0" x2="32" y2="32">
          <stop offset="0" stopColor="#6366f1" />
          <stop offset="1" stopColor="#4338ca" />
        </linearGradient>
      </defs>
    </svg>
  );
}
